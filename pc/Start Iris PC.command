#!/bin/bash
cd "$(dirname "$0")"
if command -v python3 >/dev/null 2>&1; then
  python3 IrisPC.pyw
else
  echo "Install Python 3 from python.org"
  read -r _
fi
