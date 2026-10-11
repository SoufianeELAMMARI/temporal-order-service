#!/usr/bin/env bash
# Runs every demo scenario against the app on localhost:8080.
# Usage: ./demo.sh            (all scenarios)
#        ./demo.sh happy      (one scenario: happy | approval | cancel | declined | stock | address | flaky)
set -euo pipefail

API="${API:-http://localhost:8080}"
RUN="$(date +%s)"   # unique suffix so you can rerun the script

post_order() { # id total sku address
  curl -s -X POST "$API/orders" -H 'Content-Type: application/json' -d "{
    \"orderId\": \"$1\", \"customerId\": \"cust-7\",
    \"lines\": [{\"sku\": \"$3\", \"quantity\": 2}],
    \"total\": $2, \"shippingAddress\": \"$4\" }"
  echo
}
status() { curl -s "$API/orders/$1"; echo; }
title()  { echo; echo "=== $1 ==="; }

happy() {
  title "1. Small order: ships straight away"
  post_order "happy-$RUN" 120 SKU-1 "1 Rue X, Casablanca"
  sleep 2; status "happy-$RUN"
}
approval() {
  title "2. Order over 5,000: waits for approval, then ships"
  post_order "big-$RUN" 7500 SKU-1 "1 Rue X, Casablanca"
  sleep 2; status "big-$RUN"
  echo "-> approving as alice"
  curl -s -X POST "$API/orders/big-$RUN/approve?by=alice"
  sleep 2; status "big-$RUN"
}
cancel() {
  title "3. Customer cancels while awaiting approval: stock released, payment refunded"
  post_order "cancel-$RUN" 9000 SKU-1 "1 Rue X, Casablanca"
  sleep 2
  curl -s -X POST "$API/orders/cancel-$RUN/cancel?reason=changed+my+mind"
  sleep 2; status "cancel-$RUN"
}
declined() {
  title "4. Card declined (amount > 50,000): fails fast, nothing to compensate"
  post_order "declined-$RUN" 60000 SKU-1 "1 Rue X, Casablanca"
  sleep 2; status "declined-$RUN"
}
stock() {
  title "5. Out of stock (SKU starts with OUT-): payment refunded"
  post_order "stock-$RUN" 200 OUT-42 "1 Rue X, Casablanca"
  sleep 2; status "stock-$RUN"
}
address() {
  title "6. Carrier rejects address: stock released, then payment refunded"
  post_order "address-$RUN" 200 SKU-1 "INVALID street"
  sleep 2; status "address-$RUN"
}
flaky() {
  title "7. Payment provider returns 503 twice: Temporal retries, order completes"
  post_order "flaky-$RUN" 150 SKU-1 "1 Rue X, Casablanca"
  echo "(retries back off 1s then 2s...)"
  sleep 6; status "flaky-$RUN"
}

if [[ $# -gt 0 ]]; then "$1"; else happy; approval; cancel; declined; stock; address; flaky; fi
echo; echo "Open http://localhost:8233 to see every workflow's event history."
