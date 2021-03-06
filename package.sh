#!/bin/bash

SCRIPT=$(basename $0)
if [ $# -lt 1 ]; then
    echo "Usage: $SCRIPT [VERSION]"
fi
if [ ! -d "packaging" ]; then
    echo "Please run from the project root directory"
fi

rm -f gdrivefs-*.deb gdrivefs-*.jar gdrivefs-*.rpm

VERSION=$1
WORKDIR=$(mktemp -d)
DEBDIR=$WORKDIR/debian
cp -r packaging/debian $WORKDIR
mkdir $DEBDIR/usr/lib/
cp gdrivefs.jar $DEBDIR/usr/lib/gdrivefs-"$VERSION".jar
cp gdrivefs.jar gdrivefs-"$VERSION".jar
rm gdrivefs.jar
find $DEBDIR -type f | xargs sed -i "s/__PACKAGE_VERSION/$VERSION/g"
pushd $WORKDIR
dpkg --build debian
popd
mv $WORKDIR/debian.deb gdrivefs-"$VERSION".deb
rm -r $WORKDIR
