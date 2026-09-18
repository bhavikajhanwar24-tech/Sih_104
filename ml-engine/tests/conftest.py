import os

import pytest

os.environ.setdefault("SENTINELVOICE_ML_EMIT_ENABLED", "false")


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line(
        "markers",
        "slow: real-model / network acceptance tests (deselect with -m 'not slow')",
    )
