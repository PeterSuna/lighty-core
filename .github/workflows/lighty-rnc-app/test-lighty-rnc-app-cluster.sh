#!/bin/bash
#
# Copyright (c) 2022 PANTHEON.tech s.r.o. All Rights Reserved.
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v1.0 which accompanies this distribution,
# and is available at https://www.eclipse.org/legal/epl-v10.html
#

SIMULATOR_PORT=17830
READY_PODS=false
CONTROLLER_PORT=8888
HTTP_STATUS_CODES=("200" "201" "202" "204")
declare -a test_results

# Start lighty-netconf-simulator in minikube network
docker build -t lighty-netconf-simulator ${GITHUB_WORKSPACE}/.github/workflows/lighty-rnc-app/simulator
docker run -d --rm --name netconf-simulator -p$SIMULATOR_PORT:$SIMULATOR_PORT lighty-netconf-simulator:latest

# Get netconf-simulator container's IP
SIMULATOR_IP=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' netconf-simulator)

KUB_NAMES=( $(kubectl get pods --no-headers -o custom-columns=":metadata.name") )
POD_CONTROLLER_IPS=$(kubectl get pods -l app.kubernetes.io/name=lighty-rnc-app-helm -o custom-columns=":status.podIP" | xargs)

# check if simulator has opened port
for i in {1..20} ; do
    nc -z $SIMULATOR_IP $SIMULATOR_PORT
    if [ $? ]
    then
      break;
    fi
    echo "counter: $i"
    sleep 1
done

stopTest() {
  # Check if some test failed
  if [[ ${test_results[*]} =~ 1 ]]
  then
    docker stop netconf-simulator
    # Show Logs for every pod
    pod_names=$(minikube kubectl -- get pods --no-headers -o custom-columns=":metadata.name")
    for pod_name in $pod_names; \
    do \
      minikube kubectl -- logs $pod_name \
    ;done

    exit 1;
  fi

  docker stop netconf-simulator
  exit 0;
}

updatePodReadyState() {
  for podReady in $(kubectl get pods -l app.kubernetes.io/name=lighty-rnc-app-helm -o custom-columns=":status.containerStatuses[*].ready" | xargs);
  do
    if [[ $podReady =~ .*"true".* ]]
    then
      echo "Pod is ready [$podReady]"
      READY_PODS=true
    else
      echo "Pod is not ready [$podReady]"
      READY_PODS=false
      break
    fi
  done
}

arePodsReady() {
  for i in {1..20} ;
    do
      updatePodReadyState
      if [ $READY_PODS ]
      then
        echo "PODS are ready"
        kubectl get pods -l app.kubernetes.io/name=lighty-rnc-app-helm -o custom-columns=":status.containerStatuses[*].ready" | xargs
        break;
      fi
      echo "Pods are not ready, counter: $i"
      sleep 5
  done
  if ! [ $READY_PODS ]
  then
    echo "PODS are not ready in required time."
    stopTest
  fi
}

arePodsReady

CTRL0_IP=$(kubectl get pod "${KUB_NAMES[0]}" -o custom-columns=":status.podIP" | xargs)
CTRL1_IP=$(kubectl get pod "${KUB_NAMES[1]}" -o custom-columns=":status.podIP" | xargs)
CTRL2_IP=$(kubectl get pod "${KUB_NAMES[2]}" -o custom-columns=":status.podIP" | xargs)
CTRL0_NAME=${KUB_NAMES[0]}
CTRL1_NAME=${KUB_NAMES[1]}
CTRL2_NAME=${KUB_NAMES[2]}

echo "Controller0 IPS set to: " $CTRL0_IP " running in pod name: " $CTRL0_NAME
echo "Controller1 IPS set to: " $CTRL1_IP " running in pod name: " $CTRL1_NAME
echo "Controller2 IPS set to: " $CTRL2_IP " running in pod name: " $CTRL2_NAME

# List pods
minikube kubectl -- get pods

# List Services
minikube kubectl -- get services

printLine() {
  printf '%.0s-' {1..100}; echo ""
}

assertHttpStatusCode() {
  printLine
  if [[ ${HTTP_STATUS_CODES[*]} =~ $1 ]]
  then
    echo -e "HTTP request methods: $2\nURL: $3\nStatus Code: $1\nTest passed\n"
    test_results+=(0)
  else
    echo -e "HTTP request methods: $2\nURL: $3\nStatus Code: $1\nTest failed\n"
    test_results+=(1)
  fi
}

assertNodeConnected() {
  if [[ $1 =~ .*"connected".* ]]
  then
    echo "connected"
  else
    echo "$1"
  fi
}

assertPodsTopologyResponse() {
  local previousResponse=""
  for pod_controller_ip in $POD_CONTROLLER_IPS;
  do
    TOPOLOGY_RESPONSE=$(curl --request GET \
      'http://'$pod_controller_ip:$CONTROLLER_PORT'/restconf/data/network-topology:network-topology')
      if [[ -z "$TOPOLOGY_RESPONSE" ]]
      then
        echo "Empty response from pod ip: " $pod_controller_ip
        test_results+=(1)
      elif [[ -z "$previousResponse" ]]
      then
        # First request
        echo "first response" $TOPOLOGY_RESPONSE
        previousResponse=$TOPOLOGY_RESPONSE
      elif [[ "$previousResponse" != "$TOPOLOGY_RESPONSE" ]]
      then
        echo "Previous response doesn't match: [ $previousResponse ] wtih current resposne : [ $TOPOLOGY_RESPONSE ] "
        test_results+=(1)
      fi
      echo "Succes compare for IP " $pod_controller_ip
  done
}

printLine
echo "-- Lighty-rcgnmi-app curl tests --"

# Cluster state (:8558/cluster/members)
for pod_controller_ip in $POD_CONTROLLER_IPS; \
do \
  assertHttpStatusCode $(curl -o /dev/null -s -w "%{http_code} GET %{url_effective}\n" \
   -H "Content-Type: application/json" \
   http://$pod_controller_ip:8558/cluster/members) \
;done
sleep 1

# Pods healthcheck (:8888/restconf/operations)

for pod_controller_ip in $POD_CONTROLLER_IPS; \
do \
  assertHttpStatusCode $(curl -o /dev/null -s -w "%{http_code} GET %{url_effective}\n" \
   -H "Content-Type: application/json" \
   http://$pod_controller_ip:$CONTROLLER_PORT/restconf/operations) \
;done
sleep 1

# Add node into topology
  assertHttpStatusCode $(curl -X PUT -o /dev/null -s -w "%{http_code} PUT %{url_effective}\n" \
  http://"$CTRL0_IP":$CONTROLLER_PORT/restconf/data/network-topology:network-topology/topology=topology-netconf/node=node-"${SIMULATOR_IP//.}" \
  -H 'Content-Type: application/json' \
  -d '{
      "netconf-topology:node" :[
    	{
	      "node-id": "node-'"${SIMULATOR_IP//.}"'",
	      "host": "'"$SIMULATOR_IP"'",
        "port": '"$SIMULATOR_PORT"',
	      "username": "admin",
	      "password": "admin",
	      "tcp-only": false,
	      "keepalive-delay": 0
      }
    ]
  }')
sleep 1

printLine
echo "Check if netconf-simulator is connected"
connection_status="not-connected"
for i in {1..20} ; do
  connection_status=$(assertNodeConnected $(curl -X GET -s \
  'http://'"$CTRL0_IP"':'"$CONTROLLER_PORT"'/restconf/data/network-topology:network-topology/topology=topology-netconf/node='node-"${SIMULATOR_IP//.}"'/netconf-node-topology:connection-status'))
  echo -e "Connection status: $connection_status"
  if [[ $connection_status == "connected" ]]
  then
    echo -e "Test passed\n"
    break;
  fi
  sleep 1
done

for pod_controller_ip in $POD_CONTROLLER_IPS; \
do \
  assertHttpStatusCode $(curl -o /dev/null -s -w "%{http_code} GET %{url_effective}\n" \
    -H "Content-Type: application/json" \
     http://$pod_controller_ip:$CONTROLLER_PORT/restconf/data/network-topology:network-topology) \
;done
sleep 1

## Assert if topology response is equals for every cluster
assertPodsTopologyResponse

echo "Test resize deployment to 5 clusters"
echo "Show deployment"
minikube kubectl get deployments

echo "Scale replicas to 5"
kubectl scale deployments/lighty-rnc-app-lighty-rnc-app-helm --replicas=5
sleep 40

echo "Show pods"
# List pods
minikube kubectl -- get pods

POD_CONTROLLER_IPS=$(kubectl get pods -l app.kubernetes.io/name=lighty-rnc-app-helm -o custom-columns=":status.podIP" | xargs)

## Assert if topology response is equals for every cluster after resize to 5 pods
assertPodsTopologyResponse

echo "Test resize deployment back to 3 clusters"
echo "Scale replicas to 3"
kubectl scale deployments/lighty-rnc-app-lighty-rnc-app-helm --replicas=3
sleep 35

echo "Show pods"
# List pods
minikube kubectl -- get pods

POD_CONTROLLER_IPS=$(kubectl get pods -l app.kubernetes.io/name=lighty-rnc-app-helm -o custom-columns=":status.podIP" | xargs)

## Assert if topology response is equals for every cluster after resize to 3 pods
assertPodsTopologyResponse

## Remove device
assertHttpStatusCode $(curl -X DELETE -o /dev/null -s -w "%{http_code} DELETE %{url_effective}\n" \
 http://"$CTRL0_IP":$CONTROLLER_PORT/restconf/data/network-topology:network-topology/topology=topology-netconf/node=node-"${SIMULATOR_IP//.}")

stopTest