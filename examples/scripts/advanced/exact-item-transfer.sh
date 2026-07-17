#!/bin/bash
# Submit/reconcile an exact item transfer using a caller-supplied idempotency UUID.
# Reuse the same operation UUID only for the exact same logical request.
# Usage: bash exact-item-transfer.sh <operation-uuid> <source-uuid> <destination-uuid> <item-id> <count>
if [ -z "$5" ]; then
  echo 'usage: exact-item-transfer.sh <operation-uuid> <source-uuid> <destination-uuid> <item-id> <count>'
else
  mkdir -p /home/player/operations
  echo operation_id=$1 > /home/player/operations/item-transfer-$1.txt
  echo source=$2,destination=$3,item=$4,requested=$5 >> /home/player/operations/item-transfer-$1.txt
  if device transfer "$1" "$2" "$3" "$4" "$5" >> /home/player/operations/item-transfer-$1.txt; then
    echo admission=accepted >> /home/player/operations/item-transfer-$1.txt
  else
    echo admission=failed >> /home/player/operations/item-transfer-$1.txt
  fi
  cat /home/player/operations/item-transfer-$1.txt
fi
