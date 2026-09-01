#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
build_dir="${script_dir}/build-mingw"
compiler="${MINGW_CXX:-x86_64-w64-mingw32-g++}"

if ! command -v cmake >/dev/null 2>&1; then
  echo "error: cmake was not found" >&2
  exit 1
fi

if ! command -v "${compiler}" >/dev/null 2>&1; then
  echo "error: ${compiler} was not found (install an x86_64 MinGW-w64 toolchain)" >&2
  exit 1
fi

cmake -S "${script_dir}" -B "${build_dir}" \
  -DCMAKE_SYSTEM_NAME=Windows \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_CXX_COMPILER="${compiler}"

cmake --build "${build_dir}" --config Release --parallel

echo "built: ${build_dir}/mnote.exe"
