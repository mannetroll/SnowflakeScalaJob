"""Snowflake client driven by a ``snowflake.properties`` file.

Opens a connection with key-pair authentication (a PEM-encoded PKCS#8 RSA or
ECDSA private key), falling back to password authentication when no key is
configured.

    python connect/snowflake_connection.py
    python connect/snowflake_connection.py --query "select current_version()"
    python connect/snowflake_connection.py --properties /path/to/snowflake.properties

The private-key passphrase is read from the SNOWFLAKE_PRIVATE_KEY_PASSPHRASE
environment variable, or from ``private_key_passphrase`` in the properties file.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import snowflake.connector
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PROPERTIES = PROJECT_ROOT / "snowflake.properties"
PASSPHRASE_ENV_VAR = "SNOWFLAKE_PRIVATE_KEY_PASSPHRASE"

# Properties passed straight through to snowflake.connector.connect().
CONNECTION_KEYS = (
    "account",
    "user",
    "role",
    "warehouse",
    "database",
    "schema",
    "host",
    "port",
    "authenticator",
)

SESSION_QUERY = """
select current_user()      as user,
       current_account()   as account,
       current_role()      as role,
       current_warehouse() as warehouse,
       current_database()  as database,
       current_schema()    as schema,
       current_version()   as version
"""


class ConfigError(Exception):
    """Raised when the properties file or the private key is unusable."""


def load_properties(path: Path) -> dict[str, str]:
    """Parse a Java-style ``.properties`` file into a dict.

    Blank lines and ``#``/``!`` comments are skipped, keys are separated from
    values by the first ``=`` or ``:``, and blank values are dropped so that
    commented-out placeholders behave the same as absent ones.
    """
    if not path.is_file():
        raise ConfigError(f"Properties file not found: {path}")

    props: dict[str, str] = {}
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line[0] in "#!":
            continue
        separators = [line.index(sep) for sep in "=:" if sep in line]
        if not separators:
            raise ConfigError(f"{path}:{lineno}: expected 'key = value', got {raw!r}")
        idx = min(separators)
        key = line[:idx].strip()
        value = line[idx + 1 :].strip()
        if key and value:
            props[key] = value
    return props


def load_private_key(path: Path, passphrase: str | None) -> bytes:
    """Load a PEM private key and return it as PKCS#8 DER, as the connector wants."""
    if not path.is_file():
        raise ConfigError(f"Private key not found: {path}")

    data = path.read_bytes()
    if data.lstrip().startswith(b"-----BEGIN OPENSSH PRIVATE KEY-----"):
        raise ConfigError(
            f"{path} is an OpenSSH key. Snowflake needs a PEM-encoded PKCS#8 key "
            "('-----BEGIN PRIVATE KEY-----' or '-----BEGIN ENCRYPTED PRIVATE KEY-----'). "
            "See the README for the openssl commands that generate one."
        )

    secret = passphrase.encode() if passphrase else None
    try:
        key = serialization.load_pem_private_key(data, password=secret)
    except TypeError as exc:
        if secret is None:
            raise ConfigError(
                f"{path} is encrypted. Set the {PASSPHRASE_ENV_VAR} environment "
                "variable or 'private_key_passphrase' in the properties file."
            ) from exc
        # A passphrase was supplied for an unencrypted key; ignore it.
        key = serialization.load_pem_private_key(data, password=None)
    except ValueError as exc:
        raise ConfigError(
            f"Could not read {path}: {exc}. Either the passphrase is wrong or the "
            "file is not a PEM-encoded private key."
        ) from exc

    if not isinstance(key, (rsa.RSAPrivateKey, ec.EllipticCurvePrivateKey)):
        raise ConfigError(
            f"{path} holds a {type(key).__name__.removeprefix('_')} key. Snowflake "
            "key-pair authentication supports only RSA (2048-bit or larger) and "
            "ECDSA P-256/P-384/P-521 keys."
        )
    if isinstance(key, rsa.RSAPrivateKey) and key.key_size < 2048:
        raise ConfigError(
            f"{path} is a {key.key_size}-bit RSA key; Snowflake requires at least 2048 bits."
        )

    return key.private_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )


def build_connect_kwargs(props: dict[str, str], properties_path: Path) -> dict[str, object]:
    """Turn parsed properties into keyword arguments for ``connect()``."""
    missing = [key for key in ("account", "user") if key not in props]
    if missing:
        raise ConfigError(
            f"{properties_path} is missing required setting(s): {', '.join(missing)}"
        )

    kwargs: dict[str, object] = {key: props[key] for key in CONNECTION_KEYS if key in props}

    key_path = props.get("private_key_path")
    if key_path:
        # Relative paths are resolved against the properties file, so the config
        # works no matter which directory the script is run from.
        resolved = Path(key_path).expanduser()
        if not resolved.is_absolute():
            resolved = properties_path.parent / resolved
        passphrase = os.environ.get(PASSPHRASE_ENV_VAR) or props.get("private_key_passphrase")
        kwargs["private_key"] = load_private_key(resolved, passphrase)
    elif props.get("password"):
        kwargs["password"] = props["password"]
    else:
        raise ConfigError(
            f"{properties_path} sets neither 'private_key_path' nor 'password'."
        )

    return kwargs


def connect(properties_path: Path = DEFAULT_PROPERTIES):
    """Open a Snowflake connection using the given properties file."""
    props = load_properties(properties_path)
    return snowflake.connector.connect(**build_connect_kwargs(props, properties_path))


def run_query(connection, query: str) -> None:
    """Execute a query and print the result as aligned columns."""
    with connection.cursor() as cursor:
        cursor.execute(query)
        columns = [col.name for col in cursor.description]
        rows = cursor.fetchall()

    if len(rows) == 1:
        width = max(len(name) for name in columns)
        for name, value in zip(columns, rows[0]):
            print(f"{name:<{width}}  {value}")
        return

    widths = [
        max(len(name), *(len(str(row[i])) for row in rows)) if rows else len(name)
        for i, name in enumerate(columns)
    ]
    print("  ".join(name.ljust(w) for name, w in zip(columns, widths)))
    print("  ".join("-" * w for w in widths))
    for row in rows:
        print("  ".join(str(value).ljust(w) for value, w in zip(row, widths)))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--properties",
        type=Path,
        default=DEFAULT_PROPERTIES,
        help=f"path to the properties file (default: {DEFAULT_PROPERTIES})",
    )
    parser.add_argument(
        "--query",
        default=SESSION_QUERY,
        help="SQL to run (default: report the current session)",
    )
    args = parser.parse_args(argv)

    try:
        connection = connect(args.properties)
    except ConfigError as exc:
        print(f"Configuration error: {exc}", file=sys.stderr)
        return 1
    except snowflake.connector.errors.Error as exc:
        print(f"Snowflake refused the connection: {exc}", file=sys.stderr)
        return 1

    try:
        run_query(connection, args.query)
    except snowflake.connector.errors.Error as exc:
        print(f"Query failed: {exc}", file=sys.stderr)
        return 1
    finally:
        connection.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
