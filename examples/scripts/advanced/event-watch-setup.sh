#!/bin/bash
# Create caller-owned best-effort subscriptions. Save the printed UUIDs manually;
# command substitution is intentionally unavailable in TerminalCraft.
# Usage: bash event-watch-setup.sh
mkdir -p /home/player/events
echo 'TerminalCraft event subscriptions' > /home/player/events/subscriptions.txt
echo 'Record each returned subscription UUID below.' >> /home/player/events/subscriptions.txt
device events subscribe '*' 'media_changed' 2 true >> /home/player/events/subscriptions.txt
device events subscribe '*' 'monitor_resize,output_changed,touch' 5 true >> /home/player/events/subscriptions.txt
device events subscribe '*' 'message_received' 0 false >> /home/player/events/subscriptions.txt
cat /home/player/events/subscriptions.txt
echo 'Poll with: device events poll <subscription-uuid> 32'
echo 'Inspect with: device events diagnostics <subscription-uuid>'
