#!/bin/bash
modem hostname "$1" && modem network "$2" && modem open 80 && modem open 81 && modem service add status 80
modem channels
modem service list
