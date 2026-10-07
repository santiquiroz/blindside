#!/usr/bin/env bash
# Test-only TLS fixtures for TakTlsTest (password: atakatak). Never real keys.
# Makes: truststore-root.p12 (CA as trusted entry), server.p12 (CN=opentakserver),
# player.p12 (CN=player), wrong-truststore.p12 (key entry only, no trusted certs).
set -euo pipefail

KT="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin/keytool"
D="$(cd "$(dirname "$0")" && pwd)"
PASS="atakatak"

rm -f "$D/ca.p12"
"$KT" -genkeypair -alias ca -keyalg RSA -keysize 2048 -dname "CN=test-ca" \
  -ext bc:c -validity 36500 -keystore "$D/ca.p12" -storetype PKCS12 -storepass "$PASS"
"$KT" -exportcert -alias ca -keystore "$D/ca.p12" -storepass "$PASS" -rfc -file "$D/ca.pem"
rm -f "$D/truststore-root.p12"
"$KT" -importcert -noprompt -alias ca -file "$D/ca.pem" \
  -keystore "$D/truststore-root.p12" -storetype PKCS12 -storepass "$PASS"

make_signed() {
  local name="$1" cn="$2"
  rm -f "$D/$name.p12" "$D/$name.csr" "$D/$name.pem"
  "$KT" -genkeypair -alias "$name" -keyalg RSA -keysize 2048 -dname "CN=$cn" \
    -validity 36500 -keystore "$D/$name.p12" -storetype PKCS12 -storepass "$PASS"
  "$KT" -certreq -alias "$name" -keystore "$D/$name.p12" -storepass "$PASS" -file "$D/$name.csr"
  "$KT" -gencert -alias ca -keystore "$D/ca.p12" -storepass "$PASS" \
    -infile "$D/$name.csr" -outfile "$D/$name.pem" -validity 36500 -rfc
  # The CA goes in first so keytool can chain the signed reply to it.
  "$KT" -importcert -noprompt -alias ca -file "$D/ca.pem" \
    -keystore "$D/$name.p12" -storepass "$PASS"
  "$KT" -importcert -noprompt -alias "$name" -file "$D/$name.pem" \
    -keystore "$D/$name.p12" -storepass "$PASS"
}

make_signed server opentakserver
make_signed player player

rm -f "$D/wrong-truststore.p12"
"$KT" -genkeypair -alias wrong -keyalg RSA -keysize 2048 -dname "CN=wrong" \
  -validity 36500 -keystore "$D/wrong-truststore.p12" -storetype PKCS12 -storepass "$PASS"

rm -f "$D/ca.p12" "$D/ca.pem" "$D/server.csr" "$D/server.pem" "$D/player.csr" "$D/player.pem"
ls -l "$D"
