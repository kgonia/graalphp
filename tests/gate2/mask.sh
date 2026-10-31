# Sourced by tests/gate2/http.sh and native.sh: mask(), a filter over a response.
# what differs between two correct responses: nonces (10 hex digits, which a logged-in user's depend on the random
# session token), timestamps, the seconds in wp-admin's date fields, generated passwords; and the C library's texts
# for EAI_NONAME and ETIMEDOUT, which PHP's image (Alpine, musl) words otherwise than glibc
mask() {
  sed -E -e 's/(nonce|_wpnonce|"nonce")([^0-9a-f]{1,12})[0-9a-f]{10}/\1\2NONCE/g' -e 's/data-pw="[^"]*"/data-pw="PW"/' \
    -e 's/(\x22|\x27)[0-9a-f]{10}(\x22|\x27)/\1NONCE\2/g' \
    -e 's/(<div class="ss">|name="ss" value=")[0-9]{2}/\1SS/g' -e 's/"[0-9]{10}:/"TIME:/g' \
    -e 's/[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}:[0-9]{2}/DATETIME/g' \
    -e 's/[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9:]{8} [+-][0-9]{4}/DATE/g' \
    -e 's/ver=[0-9a-f.-]+/ver=V/g' \
    -e 's/failed: Name does not resolve/failed: Name or service not known/g' -e 's/Operation timed out/Connection timed out/g'
}
