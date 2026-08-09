#!/bin/bash
# Visible control-plane acceptance check for storage telemetry, bundled control, and a monitor.
# Usage: bash production-cell-check.sh <storage> <item-id> <wire-side> <channel>
if [ -z "$4" ]; then
  echo 'usage: production-cell-check.sh <storage> <item-id> <wire-side> <channel>'
  exit 2
fi

mkdir -p /home/player/audits
REPORT=/home/player/audits/production-cell.txt
COUNT=$(storage count "$1" "$2")
BEFORE=$(wire get "$3" "$4")

echo resource=$2 > $REPORT
echo inventory="$COUNT" >> $REPORT
echo wire_before=$BEFORE >> $REPORT

monitor clear any
monitor title any 'Production Cell'
monitor set any 0 "Resource: $2"
monitor set any 1 "Inventory: $COUNT"

if wire set "$3" "$4" 15; then
  AFTER=$(wire get "$3" "$4")
  echo wire_after=$AFTER >> $REPORT
  monitor set any 2 "Control ch$4: $AFTER"
  monitor set any 3 'Acceptance: PASS'
else
  echo wire_after=failed >> $REPORT
  monitor set any 2 "Control ch$4: FAILED"
  monitor set any 3 'Acceptance: FAIL'
  cat $REPORT
  exit 1
fi

cat $REPORT
