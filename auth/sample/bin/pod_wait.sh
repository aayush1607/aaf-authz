#!/bin/bash
#########
#  ============LICENSE_START====================================================
#  org.onap.aaf
#  ===========================================================================
#  Copyright (c) 2017 AT&T Intellectual Property. All rights reserved.
#  ===========================================================================
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#  ============LICENSE_END====================================================
#
#
# A Script for use in Pods... Check for status files, and validate before moving on.
#
DIR="/opt/app/aaf/status"
APP=$1
shift
OTHER=$1
shift

function status {
  if [ -d "$DIR" ]; then
     echo "$@" > $DIR/$APP-$HOSTNAME
  fi
}


function check {
  if [ -d "$DIR" ]; then
    if [ -z "$(ls $DIR/$OTHER* 2> /dev/null)" ]; then
      echo "$DIR/$OTHER does not exist"
    else 
      echo "$(cat $DIR/$OTHER*)"
    fi
  else 
    echo "$DIR does not exist"
  fi
}

function wait {
  n=0
  while [ $n -lt 40  ]; do 
     rv="$(check)"
     echo "$rv"
     if [ -z "$(echo $rv | grep "ready")" ]; then
       (( ++n )) 
       echo "Sleep 10 (iteration $n)"
       sleep 10
     else 
       echo "$OTHER is $rv"
       n=10000
     fi
  done
}

function start {
  n=0
  while [ $n -lt 40  ]; do 
     rv="$(check)"
     echo "$OTHER is $rv"
     if [ -z "$(echo $rv | grep "ready")" ]; then
       (( ++n )) 
       echo "Sleep 10 (iteration $n)"
       sleep 10
     else 
       # This is critical.  Until status is literally "ready" in the status directory, no processes will start
       status ready
       echo "Starting $@"
       n=10000
     fi
  done
}

case "$OTHER" in
  sleep)
    echo "Sleeping $1"
    status "Sleeping $1"
    sleep $1
    shift
    status "ready"
    echo "Done"
    ;;
  stop) 
    echo "Removing $DIR/$APP-$HOSTNAME"
    rm $DIR/$APP-$HOSTNAME
    ;;
  wait)
    OTHER="$1"
    shift    
    wait
    ;;
  *)
    echo "App $APP is waiting to start until $OTHER is ready"
    status "waiting for $OTHER"
  
    start
  ;;
esac  

eval "$@"
