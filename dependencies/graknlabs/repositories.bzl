#
# Copyright (C) 2020 Grakn Labs
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as
# published by the Free Software Foundation, either version 3 of the
# License, or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#

load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

def graknlabs_dependencies():
    git_repository(
        name = "graknlabs_dependencies",
        remote = "https://github.com/graknlabs/dependencies",
        commit = "a83649f0d777440c86ffbf4ecb4bb33fd0312bf3",  # sync-marker: do not remove this comment, this is used for sync-dependencies by @graknlabs_dependencies
    )

def graknlabs_common():
    git_repository(
        name = "graknlabs_common",
        remote = "https://github.com/graknlabs/common",
        commit = "72ab1a6de9489eb43b8081eda53d29aab9e908c3" # sync-marker: do not remove this comment, this is used for sync-dependencies by @graknlabs_common
    )

def graknlabs_graql():
    git_repository(
        name = "graknlabs_graql",
        remote = "https://github.com/graknlabs/graql",
        commit = "221c3ec6c0e0b0702dc4c299ac2e39d9f6ca3a18" # sync-marker: do not remove this comment, this is used for sync-dependencies by @graknlabs_graql
    )

def graknlabs_client_java():
    git_repository(
        name = "graknlabs_client_java",
        remote = "https://github.com/alexjpwalker/client-java", # TODO: revert to graknlabs
        commit = "918be3a15467f1414a5a94c4e2b667e6fe8b0d4d",  # sync-marker: do not remove this comment, this is used for sync-dependencies by @graknlabs_client_java
    )
