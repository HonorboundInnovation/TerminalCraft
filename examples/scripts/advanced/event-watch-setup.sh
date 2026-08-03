#!/bin/bash
# Create caller-owned best-effort subscriptions and persist their returned UUIDs.
# Usage: bash event-watch-setup.sh
mkdir -p /home/player/events
echo 'TerminalCraft event subscriptions' > /home/player/events/subscriptions.txt
MEDIA_SUB=$(device events subscribe '*' 'media_changed' 2 true)
MONITOR_SUB=$(device events subscribe '*' 'monitor_resize,output_changed,touch' 5 true)
MODEM_SUB=$(device events subscribe '*' 'message_received' 0 false)
echo media=$MEDIA_SUB >> /home/player/events/subscriptions.txt
echo monitor=$MONITOR_SUB >> /home/player/events/subscriptions.txt
echo modem=$MODEM_SUB >> /home/player/events/subscriptions.txt
cat /home/player/events/subscriptions.txt
echo 'Poll with: device events poll <subscription-uuid> 32'
echo 'Inspect with: device events diagnostics <subscription-uuid>'
