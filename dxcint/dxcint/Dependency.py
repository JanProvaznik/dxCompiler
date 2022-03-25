import logging
import os
import json
import subprocess as sp
from pathlib import Path


class DependencyError(Exception):
    """
    Class to handle Dependency exceptions
    """


class Dependency(object):
    def __init__(self, config_file: str):
        self._dependencies_root = Path(os.path.join(config_file, "../..")).resolve()
        with open(config_file, "r") as config_handle:
            config = json.load(config_handle)
        # Required config parameters
        self._name = config.get("name")
        self._languages = config.get("langauges")
        self._version = config.get("version")
        self._source_link = config.get("source_link")
        # Optional config parameters
        self._package_manager = config.get("package_manager", None)
        # Computed config parameters
        self._local_dir = self._get_exec()

    @property
    def languages(self):
        return self._languages

    def link(self, symlink_destination: Path) -> Path:
        """
        Method to create a symlink for the dependency executable in the asset resources
        :param symlink_destination: Path. Dir name of the
        :return: Path. Path of a symlink
        """
        link_path = Path(os.path.join(symlink_destination, Path(self._local_dir).name))
        os.link(self._local_dir, link_path)
        return link_path

    def update_dot_env(self, home_dir: Path) -> bool:
        """
        Method to update .env file with current dependency
        :param home_dir: Path. Perspective "home" directory in asset "resources"
        :return:
        """
        if not str(home_dir).endswith("home/dnanexus"):
            raise DependencyError(
                f"Dependency._update_dot_env(): provided home dir does not end with `home/dnanexus` - .env file will "
                f"not have a desired effect on the platform. Provided path `{str(home_dir)}`"
            )
        dot_env = f"{self._name}={self._version}\n"
        dot_env_file = os.path.join(home_dir, ".env")
        with open(dot_env_file, "a") as dot_env_handle:
            dot_env_handle.write(dot_env)
        return True

    def _get_exec(self) -> Path:
        """
        Method to download (if not available) the dependency executable
        :return: Path. Location of a downloaded binary exec
        """
        local_dependency_dir = os.path.join(
            self._dependencies_root, self._name, self._version, self._name
        )
        if os.path.exists(local_dependency_dir):
            return Path(local_dependency_dir)
        else:
            os.makedirs(local_dependency_dir, exist_ok=True)
            logging.info(f"Downloading {self._name} version {self._version}")
            try:
                download_cmd = ["wget", self._source_link, "-O", local_dependency_dir]
                sp.check_call(download_cmd)
            except sp.CalledProcessError as e:
                print(e.stdout)
                print(e.stderr)
                raise e
            os.chmod(local_dependency_dir, 0o775)
        return Path(local_dependency_dir)
