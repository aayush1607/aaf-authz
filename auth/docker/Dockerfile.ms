FROM onap/aaf/aaf_core:${AAF_VERSION}
MAINTAINER AAF Team, AT&T 2018
ENV VERSION=${AAF_VERSION}

LABEL description="aaf_${AAF_COMPONENT}"
LABEL version=${AAF_VERSION}

COPY theme /opt/app/aaf/theme

CMD ["/bin/bash","-c","/opt/app/aaf/bin/${AAF_COMPONENT}"]

# For Debugging installation
# CMD ["/bin/bash","-c","pwd;cd /opt/app/osaaf;find /opt/app/osaaf -depth;df -k; cat /opt/app/aaf/${AAF_COMPONENT}/bin/${AAF_COMPONENT};cat /etc/hosts;/opt/app/aaf/${AAF_COMPONENT}/bin/${AAF_COMPONENT}"]
# Java Debugging VM Args
#     "-Xdebug",\
#     "-Xnoagent",\
#     "-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=8000",\

# TLS Debugging VM Args
#     "-Djavax.net.debug","ssl", \
     