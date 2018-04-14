#!/usr/bin/env bash


read_dom () {
    local IFS=\>
    read -d \< ENTITY CONTENT
    local RET=$?
    TAG_NAME=${ENTITY%% *}
    ATTRIBUTES=${ENTITY#* }
    return $RET
}

get_target_name () {

    pomf=${1:-pom.xml}

    fatal_check_file $pomf

    let CTR=0
    JARNAME=''
    JVER=''
    JPKG=''

    while read_dom; do

        if [[ $ENTITY = "artifactId" ]]; then
            JARNAME="$CONTENT"
            ((CTR+=1))
        elif [[ $ENTITY = "version" ]]; then
            JVER="$CONTENT"
            ((CTR+=1))
        elif [[ $ENTITY = "packaging" ]]; then
            JPKG="$CONTENT"
            ((CTR+=1))
        fi
        if [[ $CTR -eq 3 ]]; then
            echo "${JARNAME}-${JVER}.${JPKG}"
            break;
        fi
    done < $pomf
}


fatal_check_file () {

    if [ ! -f "$1" ]; then
        echo "File '$1' not found";
        exit 2;
    fi
    return 0;
}


fatal_check_bolt () {
# 'bolt://localhost:7476'
	pid=''
	gport=7476
	if [ -z $1 ]; then
		GRAPHENEDB_BOLT_URL='bolt://localhost:7476'
	else
		GRAPHENEDB_BOLT_URL=$1
	fi


	path_regex="bolt:\/\/(.+):([0-9]+)"

	[[ $GRAPHENEDB_BOLT_URL =~ $path_regex ]] && ghost="${BASH_REMATCH[1]}" && gport="${BASH_REMATCH[2]}";

	nc -z $ghost $gport >/dev/null 2>&1;

    if [ $? -eq 1 ]; then
        echo "Bolt not running";
        exit 3;
	fi

    return 0;
}

stop_zqueue () {

	pid=''

	nc -z localhost $QUEUE_PORT >/dev/null 2>&1;

    if [ $? -eq 0 ]; then

		if [ ! -z "$ZQUEUE_PIDFILE" ] && [ -f $ZQUEUE_PIDFILE ]; then

			let SLEEP=4
			pid=`cat "$ZQUEUE_PIDFILE"`
			while [ $SLEEP -ge 0 ] && [ -f "$ZQUEUE_PIDFILE" ]; do

				kill -9 "$pid"

				if [ $? -eq 0 ]; then
					rm -f "$ZQUEUE_PIDFILE"  2>&1
					echo  "Killed Process: $pid" >&2
					return 0
				else
					sleep $SLEEP
					((SLEEP-=1))
				fi
			done
		else

			pid=`ps -eo 'tty pid args' | grep "$B2_JARFILE" | grep -v grep | tr -s ' ' | cut -f2 -d ' '`

			if [ ! -z "$pid" ]; then
				echo  "Killed Process: $pid"
				kill -KILL $pid  2>&1
			fi

			pid=`ps -eo 'tty pid args' | grep "$PRG" | grep -v grep | tr -s ' ' | cut -f2 -d ' '`
			if [ ! -z "$pid" ]; then
				ps -p $pid  2>&1
				if [ $? -eq 0 ]; then
					echo  "Killing process: $pid" >&2
					kill -9 $pid 1
					return 0
				fi
			fi

		fi
	else
		echo "Not running"
	fi
}

function start_zqueue () {
	JAR="$1";

#	java $JAVA_OPTS -jar b2intgr/target/b2intgr-0.0.1.jar &

	echo -n $! > $ZQUEUE_PIDFILE
 
 	nc -z localhost $QUEUE_PORT >/dev/null 2>&1;
 
	if [ $? -eq 0 ]; then
		echo "Already running on port: ${QUEUE_PORT}" >&2
		return 0

		# port $QUEUE_PORT is not available! >&2
		# stop_zqueue
	fi
 
	java $JAVA_OPTS -jar $JAR & 

    let pid=$!

 	## Store PID
	echo  -n $pid > $ZQUEUE_PIDFILE

 	echo  "Starting 'Router' (pid: ${pid}) ..." >&2
}


test_zqueue () {

	nc -z localhost $QUEUE_PORT >/dev/null 2>&1

    if [ $? -eq 0 ]; then
		echo "Application is running. Exiting ..." >&2
		return 0
	fi

	MAVEN="`which mvn`"
			
    $MAVEN clean test [ "$B2_HOME" == "." ] && '' || "--file $B2_HOME"
    

    return 0;
}

