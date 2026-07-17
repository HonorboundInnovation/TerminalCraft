#!/bin/bash
# Queue a bounded three-stage operator-board update on an adjacent server rack.
# Each returned job UUID is caller-owned; record it for status/cancellation.
# Usage: bash rack-dashboard-jobs.sh
mkdir -p /home/player/jobs
echo 'Submitted rack jobs' > /home/player/jobs/last-batch.txt
server submit monitor title any 'Automated Operations' >> /home/player/jobs/last-batch.txt
server submit monitor set any 0 'Stage 1: admitted' >> /home/player/jobs/last-batch.txt
server submit monitor set any 1 'Stage 2: scheduler active' >> /home/player/jobs/last-batch.txt
server list >> /home/player/jobs/last-batch.txt
server scheduler >> /home/player/jobs/last-batch.txt
cat /home/player/jobs/last-batch.txt
