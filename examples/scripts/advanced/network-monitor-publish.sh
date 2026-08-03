#!/bin/bash
# Pocket-terminal client for a receiver configured with:
#   modem open 42
#   monitor service add factory-wall 42

modem open 41 || exit 1
monitor remote factory-wall title 'Factory Status' || exit 1
monitor remote factory-wall color '#66ff99' '#050a05' || exit 1
monitor remote factory-wall set 0 'Production online' || exit 1
monitor remote factory-wall set 1 'Published over RedNet' || exit 1
