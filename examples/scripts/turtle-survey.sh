#!/bin/bash
echo survey-start > /home/player/survey.txt
for side in front top bottom left right back; do
  echo side=$side >> /home/player/survey.txt
  turtle inspect $side >> /home/player/survey.txt
done
cat /home/player/survey.txt
