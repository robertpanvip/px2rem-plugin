#!/usr/bin/env bash
# 已迁移到 scripts/run_tests.sh，保留此入口仅为向后兼容。
exec bash "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/scripts/run_tests.sh"
