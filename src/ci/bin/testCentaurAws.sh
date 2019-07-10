#!/usr/bin/env bash

set -o errexit -o nounset -o pipefail
export CROMWELL_BUILD_SUPPORTS_CRON=true
export CROMWELL_BUILD_REQUIRES_SECURE=true
# import in shellcheck / CI / IntelliJ compatible ways
# shellcheck source=/dev/null
source "${BASH_SOURCE%/*}/test.inc.sh" || source test.inc.sh

cromwell::build::setup_common_environment

cromwell::build::setup_centaur_environment

cromwell::build::assemble_jars


# Installing the AWS CLI
cromwell::build::pip_install awscli --upgrade


cromwell::build::run_centaur \
    -i s3inputs\

cromwell::build::generate_code_coverage
