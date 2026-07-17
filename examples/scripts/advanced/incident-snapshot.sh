#!/bin/bash
# Gather authority, scheduler, device, storage, event, and network state in one file.
# Usage: bash incident-snapshot.sh
mkdir -p /home/player/audits
echo 'TerminalCraft incident snapshot' > /home/player/audits/incident.txt
date >> /home/player/audits/incident.txt
uname >> /home/player/audits/incident.txt
label >> /home/player/audits/incident.txt
auth status >> /home/player/audits/incident.txt
server queued >> /home/player/audits/incident.txt
server scheduler >> /home/player/audits/incident.txt
device list >> /home/player/audits/incident.txt
device events 32 >> /home/player/audits/incident.txt
storage list --json >> /home/player/audits/incident.txt
modem interfaces >> /home/player/audits/incident.txt
modem neighbors 32 >> /home/player/audits/incident.txt
modem hosts >> /home/player/audits/incident.txt
modem services >> /home/player/audits/incident.txt
cat /home/player/audits/incident.txt
