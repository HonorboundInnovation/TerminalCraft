#!/bin/bash
echo 'TerminalCraft diagnostic report' > /home/player/diagnostics.txt
uname >> /home/player/diagnostics.txt
date >> /home/player/diagnostics.txt
label >> /home/player/diagnostics.txt
auth status >> /home/player/diagnostics.txt
device list >> /home/player/diagnostics.txt
storage list >> /home/player/diagnostics.txt
modem interfaces >> /home/player/diagnostics.txt
modem hosts >> /home/player/diagnostics.txt
cat /home/player/diagnostics.txt
