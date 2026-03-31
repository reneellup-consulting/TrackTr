#!/bin/sh

PRESERVECONFIG=0
if [ -f /opt/tracktr/conf/tracktr.xml ]
then
    cp /opt/tracktr/conf/tracktr.xml /opt/tracktr/conf/tracktr.xml.saved
    PRESERVECONFIG=1
fi

mkdir -p /opt/tracktr
cp -r * /opt/tracktr
chmod -R go+rX /opt/tracktr

if [ ${PRESERVECONFIG} -eq 1 ] && [ -f /opt/tracktr/conf/tracktr.xml.saved ]
then
    mv -f /opt/tracktr/conf/tracktr.xml.saved /opt/tracktr/conf/tracktr.xml
fi

mv /opt/tracktr/tracktr.service /etc/systemd/system
chmod 664 /etc/systemd/system/tracktr.service

systemctl daemon-reload
systemctl enable tracktr.service

rm /opt/tracktr/setup.sh
rm -r ../out
