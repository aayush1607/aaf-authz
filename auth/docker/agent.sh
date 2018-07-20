#!/bin/bash

CADI_VERSION=2.1.2-SNAPSHOT

# Fill out "aaf.props" if not filled out already
if [ ! -e aaf.props ]; then
  > ./aaf.props
fi
for V in VERSION AAF_FQDN DEPLOY_FQI APP_FQDN APP_FQI VOLUME DRIVER LATITUDE LONGITUDE; do
   if [ "$(grep $V ./aaf.props)" = "" ]; then
      unset DEF
      case $V in
         AAF_FQDN)   PROMPT="AAF's FQDN";;
         DEPLOY_FQI) PROMPT="Deployer's FQI";;
         APP_FQI)    PROMPT="App's FQI";; 
         APP_FQDN)   PROMPT="App's Root FQDN";; 
         VOLUME)     PROMPT="APP's AAF Configuration Volume";;
         DRIVER)     PROMPT=$V;DEF=local;;
	 VERSION)    PROMPT="CADI Version";DEF=$CADI_VERSION;;
         LATITUDE|LONGITUDE) PROMPT="$V of Node";;
         *)          PROMPT=$V;;
      esac
      if [ "$DEF" = "" ]; then
           PROMPT="$PROMPT: "
      else 
           PROMPT="$PROMPT ($DEF): "
      fi
      read -p "$PROMPT" VAR 
      if [ "$VAR" = "" ]; then
         if [ "$DEF" = "" ]; then
            echo "agent.sh needs each value queried.  Please start again."
            exit
         else
            VAR=$DEF
         fi
      fi
      echo "$V=$VAR" >> ./aaf.props
   fi
done
. ./aaf.props

# Need AAF_FQDN's IP, because not might not be available in mini-container
if [ "$AAF_AAF_FQDN_IP" = "" ]; then
  AAF_AAF_FQDN_IP=$(host $AAF_FQDN | grep "has address" | tail -1 | cut -f 4 -d ' ')
  if [ "$AAF_AAF_FQDN_IP" = "" ]; then
    read -p "IP of $AAF_FQDN: " AAF_AAF_FQDN_IP
    echo "AAF_AAF_FQDN_IP=$AAF_AAF_FQDN_IP" >> ./aaf.props
  fi
fi

# Make sure Container Volume exists
if [ "$(docker volume ls | grep ${VOLUME})" = "" ]; then
  echo -n "Creating Volume: " 
  docker volume create -d ${DRIVER} ${VOLUME}
fi

docker run \
    -it \
    --rm \
    --mount 'type=volume,src='${VOLUME}',dst=/opt/app/osaaf,volume-driver='${DRIVER} \
    --add-host="$AAF_FQDN:$AAF_AAF_FQDN_IP" \
    --env AAF_FQDN=${AAF_FQDN} \
    --env DEPLOY_FQI=${DEPLOY_FQI} \
    --env DEPLOY_PASSWORD=${DEPLOY_PASSWORD} \
    --env APP_FQI=${APP_FQI} \
    --env APP_FQDN=${APP_FQDN} \
    --env LATITUDE=${LATITUDE} \
    --env LONGITUDE=${LONGITUDE} \
    --name aaf_agent_$USER \
    onap/aaf/aaf_agent:$VERSION \
    /bin/bash "$@"