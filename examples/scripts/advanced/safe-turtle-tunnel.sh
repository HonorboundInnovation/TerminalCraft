#!/bin/bash
# Bounded tunnel that records the first failed stage and stops immediately.
# Usage: bash safe-turtle-tunnel.sh
COMPLETED=0
echo tunnel-start > /home/player/tunnel-run.log
for step in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do
  echo step=$step >> /home/player/tunnel-run.log
  if turtle dig front; then
    if turtle forward; then
      if turtle dig top; then
        let COMPLETED=COMPLETED+1
        echo step=$step,status=complete >> /home/player/tunnel-run.log
      else
        echo step=$step,status=ceiling-failed >> /home/player/tunnel-run.log
        break
      fi
    else
      echo step=$step,status=move-failed >> /home/player/tunnel-run.log
      break
    fi
  else
    echo step=$step,status=front-dig-failed >> /home/player/tunnel-run.log
    break
  fi
done
echo completed=$COMPLETED >> /home/player/tunnel-run.log
cat /home/player/tunnel-run.log
