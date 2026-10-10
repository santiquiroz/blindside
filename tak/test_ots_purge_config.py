import importlib.util
from datetime import datetime, timedelta
from pathlib import Path

spec = importlib.util.spec_from_file_location("ots_purge_config", Path(__file__).with_name("ots-purge-config.py"))
purge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(purge)

NOW = datetime(2026, 10, 9, 23, 0, 0)
THIRTY = timedelta(minutes=30)


def package(name, minutes_ago):
    uploaded = (NOW - timedelta(minutes=minutes_ago)).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-2] + "Z"
    return {"filename": name, "submission_time": uploaded, "hash": name}


def names(packages):
    return [p["filename"] for p in packages]


def test_old_connection_packages_are_stale():
    old = [package("lucho_CONFIG.zip", 31), package("lucho_CONFIG_iTAK.zip", 45)]
    assert names(purge.stale_config_packages(old, NOW, THIRTY)) == ["lucho_CONFIG.zip", "lucho_CONFIG_iTAK.zip"]


def test_fresh_connection_packages_wait():
    assert purge.stale_config_packages([package("ana_CONFIG.zip", 5)], NOW, THIRTY) == []


def test_maps_and_field_package_are_never_touched():
    keep = [package("cementera-op-medusa-paquete.zip", 600), package("bing-aerial.zip", 600),
            package("CONFIG.zip", 600), package("notes_CONFIG.txt", 600)]
    assert purge.stale_config_packages(keep, NOW, THIRTY) == []


def test_missing_or_bad_time_is_skipped():
    weird = [{"filename": "x_CONFIG.zip", "submission_time": None, "hash": "a"},
             {"filename": "y_CONFIG.zip", "submission_time": "ayer", "hash": "b"}]
    assert purge.stale_config_packages(weird, NOW, THIRTY) == []


def test_server_local_time_with_z_is_not_read_as_utc():
    # Uploaded one minute ago in the server's local time, stamped with a "Z": must not look five hours old.
    assert purge.stale_config_packages([package("nuevo_CONFIG.zip", 1)], NOW, THIRTY) == []
