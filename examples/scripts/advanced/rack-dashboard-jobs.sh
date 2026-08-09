#!/bin/bash
# Queue a bounded three-stage operator-board update on an adjacent server rack.
# Each returned job UUID is caller-owned and recorded for status/cancellation.
# Usage: bash rack-dashboard-jobs.sh
mkdir -p /home/player/jobs
echo 'Submitted rack jobs' > /home/player/jobs/last-batch.txt
TITLE_JOB=$(server submit monitor title any 'Automated Operations')
STAGE_ONE_JOB=$(server submit monitor set any 0 'Stage 1: admitted')
STAGE_TWO_JOB=$(server submit monitor set any 1 'Stage 2: scheduler active')
echo title=$TITLE_JOB >> /home/player/jobs/last-batch.txt
echo stage_one=$STAGE_ONE_JOB >> /home/player/jobs/last-batch.txt
echo stage_two=$STAGE_TWO_JOB >> /home/player/jobs/last-batch.txt
server list >> /home/player/jobs/last-batch.txt
server scheduler >> /home/player/jobs/last-batch.txt
cat /home/player/jobs/last-batch.txt
