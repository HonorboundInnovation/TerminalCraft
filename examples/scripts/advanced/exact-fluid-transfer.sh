#!/bin/bash
# Submit/reconcile an exact fluid transfer using a caller-supplied idempotency UUID.
# Amount is in millibuckets. Preserve the UUID when reconciling an uncertain result.
# Usage: bash exact-fluid-transfer.sh <operation-uuid> <source-uuid> <destination-uuid> <fluid-id> <amount-mB>
if [ -z "$5" ]; then
  echo 'usage: exact-fluid-transfer.sh <operation-uuid> <source-uuid> <destination-uuid> <fluid-id> <amount-mB>'
else
  mkdir -p /home/player/operations
  echo operation_id=$1 > /home/player/operations/fluid-transfer-$1.txt
  echo source=$2,destination=$3,fluid=$4,requested_mB=$5 >> /home/player/operations/fluid-transfer-$1.txt
  if device fluid-transfer "$1" "$2" "$3" "$4" "$5" >> /home/player/operations/fluid-transfer-$1.txt; then
    echo admission=accepted >> /home/player/operations/fluid-transfer-$1.txt
  else
    echo admission=failed >> /home/player/operations/fluid-transfer-$1.txt
  fi
  cat /home/player/operations/fluid-transfer-$1.txt
fi
