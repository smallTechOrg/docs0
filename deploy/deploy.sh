#!/usr/bin/env bash
# docs0 hosted deploy: Cloud Run service `docs0` (scale to zero, Slack events over HTTP), two Cloud Run Jobs
# (`docs0-sync`, `docs0-digest`) triggered by Cloud Scheduler, a database role on the EXISTING Cloud SQL instance.
#
#   deploy/deploy.sh                 DRY RUN (default): prints every command, executes nothing, touches no cloud resource
#   deploy/deploy.sh --apply         runs them (after the printed plan has been reviewed)
#   deploy/deploy.sh --step deploy   only one step (repeatable): wif | db-user | deploy | jobs | scheduler | verify | domain
#   deploy/deploy.sh rollback [REVISION]   print (or with --apply run) the traffic switch back to a revision
#
# Default steps: db-user deploy jobs scheduler verify. `wif` (one-time GitHub Actions trust) and `domain` (custom
# domain mapping) only run when asked for with --step.
#
# There is NO Secret Manager: every setting, secret or not, is a plain environment variable on Cloud Run, taken from
# THIS process's environment (GitHub Actions fills it from the `production` environment's secrets and variables; see
# README "Deploying to Cloud Run"). Values are written to a private temporary file for `--env-vars-file` and are never
# printed. This script never reads or prints a .env file; it only references variable NAMES.
# Required (apply only): DOCS0_ANTHROPIC_API_KEY DOCS0_SLACK_BOT_TOKEN DOCS0_SLACK_SIGNING_SECRET DOCS0_ENCRYPTION_KEY
#   DOCS0_ADMIN_PASSWORD DOCS0_DATABASE_PASSWORD DOCS0_GOOGLE_OAUTH_CLIENT_ID DOCS0_GOOGLE_OAUTH_CLIENT_SECRET
# Optional (skipped when empty): DOCS0_CRON_TOKEN DOCS0_TENANT_SLUG DOCS0_TENANT_NAME DOCS0_MONTHLY_BUDGET_USD
#   DOCS0_ANTHROPIC_MODEL DOCS0_SLACK_HISTORY_DAYS DOCS0_GRAIN_HISTORY_DAYS DOCS0_GMAIL_HISTORY_DAYS
#   DOCS0_PROACTIVE_MAX_PER_HOUR DOCS0_STALE_DAYS DOCS0_AGENT_MAX_STEPS DOCS0_NEW_TENANT_BUDGET_USD DOCS0_TIMEZONE
#   DOCS0_ANTHROPIC_INPUT_PRICE DOCS0_ANTHROPIC_OUTPUT_PRICE DOCS0_DB_POOL_MAX
# Creates ONLY: Cloud Run service docs0, Cloud Run Jobs docs0-sync and docs0-digest, Cloud Scheduler jobs docs0-sync
# and docs0-digest, role docs0_app on ai-agent-db, WIF provider docs0-github, the domain mapping. The database
# `docs0` itself is created by the app at first boot (DOCS0_AUTO_CREATE_DATABASE=true).
set -euo pipefail

PROJECT="${PROJECT:-ai-agent-boilerplate0}"
PROJECT_NUMBER="${PROJECT_NUMBER:-870371939888}"
REGION="${REGION:-us-central1}"
INSTANCE="${INSTANCE:-ai-agent-db}"
DB_HOST="${DB_HOST:-10.110.112.7}"                       # ai-agent-db private IP (Direct VPC egress, like the sibling services)
SA="${SA:-870371939888-compute@developer.gserviceaccount.com}"
DEPLOYER_SA="${DEPLOYER_SA:-github-actions-deployer@ai-agent-boilerplate0.iam.gserviceaccount.com}"
GITHUB_REPO="${GITHUB_REPO:-smallTechOrg/docs0}"
SERVICE="${SERVICE:-docs0}"
SYNC_JOB="${SYNC_JOB:-docs0-sync}"
DIGEST_JOB="${DIGEST_JOB:-docs0-digest}"
DB_NAME="${DB_NAME:-docs0}"
DB_USER="${DB_USER:-docs0_app}"
DOMAIN="${DOMAIN:-docs0.smalltech.in}"
PUBLIC_URL="${PUBLIC_URL:-${DOCS0_PUBLIC_URL:-https://docs0.smalltech.in}}"          # what Slack and browsers use
SERVICE_URL="${SERVICE_URL:-https://${SERVICE}-${PROJECT_NUMBER}.${REGION}.run.app}"  # deterministic Cloud Run URL (works before DNS)
SYNC_SCHEDULE="${SYNC_SCHEDULE:-*/15 * * * *}"
DIGEST_SCHEDULE="${DIGEST_SCHEDULE:-0 9 * * 1}"          # Monday 09:00 (Cloud Scheduler uses 5-field cron)
SCHEDULE_TZ="${SCHEDULE_TZ:-${DOCS0_TIMEZONE:-UTC}}"

cd "$(dirname "$0")/.."    # the repo root: `--source .` and the ignore-file checks need it

APPLY=0
STEPS=()
ACTION="deploy"
ROLLBACK_REV=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1 ;;
    --step) shift; STEPS+=("${1:?--step needs a name}") ;;
    rollback) ACTION="rollback"; if [ $# -gt 1 ] && [[ "$2" != --* ]]; then shift; ROLLBACK_REV="$1"; fi ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
[ ${#STEPS[@]} -eq 0 ] && STEPS=(db-user deploy jobs scheduler verify)

if [ "$APPLY" -eq 1 ]; then echo "# MODE: APPLY (executing)"; else echo "# MODE: DRY RUN (nothing is executed; pass --apply to run)"; fi
echo "# project=$PROJECT region=$REGION instance=$INSTANCE service=$SERVICE db=$DB_NAME role=$DB_USER public_url=$PUBLIC_URL"

run() {  # print the command exactly as it will run; execute it only with --apply
  echo "\$ $*"
  if [ "$APPLY" -eq 1 ]; then eval "$*"; fi
}
note() { echo "# $*"; }

REQUIRED_ENV=(DOCS0_ANTHROPIC_API_KEY DOCS0_SLACK_BOT_TOKEN DOCS0_SLACK_SIGNING_SECRET DOCS0_ENCRYPTION_KEY
  DOCS0_ADMIN_PASSWORD DOCS0_DATABASE_PASSWORD DOCS0_GOOGLE_OAUTH_CLIENT_ID DOCS0_GOOGLE_OAUTH_CLIENT_SECRET)
OPTIONAL_ENV=(DOCS0_CRON_TOKEN DOCS0_TENANT_SLUG DOCS0_TENANT_NAME DOCS0_MONTHLY_BUDGET_USD DOCS0_ANTHROPIC_MODEL
  DOCS0_SLACK_HISTORY_DAYS DOCS0_GRAIN_HISTORY_DAYS DOCS0_GMAIL_HISTORY_DAYS DOCS0_PROACTIVE_MAX_PER_HOUR
  DOCS0_STALE_DAYS DOCS0_AGENT_MAX_STEPS DOCS0_NEW_TENANT_BUDGET_USD DOCS0_TIMEZONE DOCS0_ANTHROPIC_INPUT_PRICE
  DOCS0_ANTHROPIC_OUTPUT_PRICE DOCS0_DB_POOL_MAX)
# Not secret, fixed by this script (same values for the service and both jobs).
FIXED_ENV=("DOCS0_DATABASE_URL=jdbc:postgresql://$DB_HOST:5432/$DB_NAME?sslmode=require"
  "DOCS0_DATABASE_USER=$DB_USER" "DOCS0_AUTO_CREATE_DATABASE=true" "DOCS0_PUBLIC_URL=$PUBLIC_URL"
  "DOCS0_SLACK_TRANSPORT=http" "DOCS0_INTERNAL_SCHEDULER=false" "DOCS0_DRIVE_SYNC_ON_STARTUP=false"
  "DOCS0_SYNC_JOB_NAME=$SYNC_JOB" "DOCS0_GCP_PROJECT=$PROJECT" "DOCS0_GCP_REGION=$REGION")
# Defaults for optional values that are not set at all.
DEFAULT_ENV=("DOCS0_TENANT_SLUG=smalltech" "DOCS0_TENANT_NAME=smallTech" "DOCS0_MONTHLY_BUDGET_USD=100"
  "DOCS0_ANTHROPIC_MODEL=claude-sonnet-5-5" "DOCS0_DB_POOL_MAX=5")

ENVFILE="<env-file>"
make_env_file() {  # apply only: a 0600 JSON file of NAME->value read from the environment; deleted on exit
  local missing=() v
  for v in "${REQUIRED_ENV[@]}"; do [ -n "${!v:-}" ] || missing+=("$v"); done
  if [ ${#missing[@]} -gt 0 ]; then echo "ERROR: set these environment variables first: ${missing[*]}" >&2; exit 1; fi
  ENVFILE="$(mktemp)"; chmod 600 "$ENVFILE"; trap 'rm -f "$ENVFILE"' EXIT
  python3 -I - "$ENVFILE" "${#DEFAULT_ENV[@]}" "${DEFAULT_ENV[@]}" "${FIXED_ENV[@]}" <<'PY'
import json, os, sys
out, ndefaults, pairs = sys.argv[1], int(sys.argv[2]), sys.argv[3:]
env = {}
for pair in pairs[:ndefaults]:
    k, v = pair.split("=", 1); env[k] = v
names = os.environ.get("REQUIRED_NAMES", "").split() + os.environ.get("OPTIONAL_NAMES", "").split()
for k in names:
    if os.environ.get(k, ""):
        env[k] = os.environ[k]
for pair in pairs[ndefaults:]:
    k, v = pair.split("=", 1); env[k] = v
json.dump(env, open(out, "w"))
PY
}
describe_env() {
  note "env vars (names only; values come from this process's environment and are never printed):"
  note "  from environment, required: ${REQUIRED_ENV[*]}"
  note "  from environment, optional (skipped when empty): ${OPTIONAL_ENV[*]}"
  note "  fixed by this script: ${FIXED_ENV[*]%%=*}"
}

step_wif() {
  note "step: wif (one-time; run by someone allowed to administer IAM, not by the workflow): lets GitHub Actions of $GITHUB_REPO deploy without a key"
  run "gcloud iam workload-identity-pools providers describe docs0-github --workload-identity-pool github --location global --project $PROJECT >/dev/null 2>&1 || gcloud iam workload-identity-pools providers create-oidc docs0-github --workload-identity-pool github --location global --project $PROJECT --display-name 'docs0 GitHub Actions' --issuer-uri https://token.actions.githubusercontent.com --attribute-mapping 'google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref' --attribute-condition \"assertion.repository=='$GITHUB_REPO'\""
  run "gcloud iam service-accounts add-iam-policy-binding $DEPLOYER_SA --project $PROJECT --role roles/iam.workloadIdentityUser --member 'principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository/$GITHUB_REPO' >/dev/null"
}

step_db_user() {
  note "step: db-user (role only; the app creates database '$DB_NAME' itself on first boot and so owns it)"
  note "the password is \$DOCS0_DATABASE_PASSWORD (a GitHub environment secret you generate once, e.g. openssl rand -base64 24 | tr -d '/+=\\n'); never printed"
  if [ "$APPLY" -eq 1 ]; then : "${DOCS0_DATABASE_PASSWORD:?set DOCS0_DATABASE_PASSWORD}"; fi
  run "if gcloud sql users list --instance $INSTANCE --project $PROJECT --format='value(name)' | grep -qx $DB_USER; then gcloud sql users set-password $DB_USER --instance $INSTANCE --project $PROJECT --password \"\$DOCS0_DATABASE_PASSWORD\"; else gcloud sql users create $DB_USER --instance $INSTANCE --project $PROJECT --password \"\$DOCS0_DATABASE_PASSWORD\"; fi"
}

step_deploy() {
  note "step: deploy (Cloud Build from ./Dockerfile into Artifact Registry repo cloud-run-source-deploy, then Cloud Run)"
  note "scale to zero (min 0, max 1), request-based CPU: the first request after idle starts a container (~10 s cold start)"
  note "Slack events arrive over HTTP at $PUBLIC_URL/slack/events and $PUBLIC_URL/slack/interactions (Socket Mode is OFF in the Slack app)"
  note "upload check: .env must be excluded from the Cloud Build upload (.gcloudignore) and from the image build context (.dockerignore)"
  for f in .gcloudignore .dockerignore; do
    if grep -qxF '.env' "$f" && grep -qxF '.env.*' "$f"; then
      note "ok: $f excludes .env and .env.*"
    else
      echo "ERROR: $f does not exclude .env / .env.*; refusing to continue" >&2; exit 1
    fi
  done
  describe_env
  if [ "$APPLY" -eq 1 ]; then make_env_file; fi
  note "Direct VPC egress to the private Cloud SQL IP, same as the sibling services: --network/--subnet default, private-ranges-only"
  note "startup probe: /health, up to 18 x 10 s = 3 minutes (first boot creates the database and runs the migrations)."
  note "   If this gcloud version rejects --startup-probe, use 'gcloud beta run deploy'; Cloud Run's default TCP probe (up to 240 s) also works."
  run "gcloud run deploy $SERVICE --project $PROJECT --region $REGION --source . \\
    --service-account $SA \\
    --allow-unauthenticated --port 8080 \\
    --min-instances 0 --max-instances 1 --timeout 300 --concurrency 20 --cpu 1 --memory 1Gi --cpu-boost \\
    --network default --subnet default --vpc-egress private-ranges-only \\
    --startup-probe 'httpGet.path=/health,httpGet.port=8080,initialDelaySeconds=10,periodSeconds=10,timeoutSeconds=5,failureThreshold=18' \\
    --env-vars-file \"\$ENVFILE\""
}

step_jobs() {
  note "step: jobs (Cloud Run Jobs from the SAME image the service runs; same env, same Direct VPC egress)"
  note "$SYNC_JOB runs '--docs0.job=sync'; $DIGEST_JOB runs '--docs0.job=digest' (needs the job entrypoint from the code follow-up)"
  describe_env
  if [ "$APPLY" -eq 1 ]; then make_env_file; fi
  local name arg
  for pair in "$SYNC_JOB:sync" "$DIGEST_JOB:digest"; do
    name="${pair%%:*}"; arg="${pair##*:}"
    run "gcloud run jobs deploy $name --project $PROJECT --region $REGION \\
    --image \"\$(gcloud run services describe $SERVICE --project $PROJECT --region $REGION --format='value(spec.template.spec.containers[0].image)')\" \\
    --args=--docs0.job=$arg \\
    --service-account $SA --cpu 1 --memory 1Gi --tasks 1 --max-retries 0 --task-timeout 3600 \\
    --network default --subnet default --vpc-egress private-ranges-only \\
    --env-vars-file \"\$ENVFILE\""
    run "gcloud run jobs add-iam-policy-binding $name --project $PROJECT --region $REGION --member=serviceAccount:$SA --role=roles/run.invoker >/dev/null"
  done
}

step_scheduler() {
  note "step: scheduler (Cloud Scheduler calls the Cloud Run Admin API 'run' method as $SA; create-or-update)"
  run "gcloud services enable run.googleapis.com cloudscheduler.googleapis.com --project $PROJECT"
  local name schedule
  for pair in "$SYNC_JOB|$SYNC_SCHEDULE" "$DIGEST_JOB|$DIGEST_SCHEDULE"; do
    name="${pair%%|*}"; schedule="${pair#*|}"
    # run.googleapis.com is a Google API, so Scheduler authenticates with an OAuth access token of the service account.
    run "if gcloud scheduler jobs describe $name --location $REGION --project $PROJECT >/dev/null 2>&1; then VERB=update; else VERB=create; fi; gcloud scheduler jobs \$VERB http $name --location $REGION --project $PROJECT --schedule '$schedule' --time-zone '$SCHEDULE_TZ' --uri 'https://run.googleapis.com/v2/projects/$PROJECT/locations/$REGION/jobs/$name:run' --http-method POST --oauth-service-account-email $SA --attempt-deadline 180s"
  done
}

step_verify() {
  note "step: verify (read-only)"
  run "gcloud run services describe $SERVICE --project $PROJECT --region $REGION --format='value(status.url)'"
  run "curl -fsS -m 60 $SERVICE_URL/health"
  note "expect exactly {\"status\":\"UP\"}; the first call after idle can take ~10 s (cold start)"
  note "the status page / and /admin ask for user admin + the DOCS0_ADMIN_PASSWORD you set in the GitHub environment"
  run "gcloud scheduler jobs list --location $REGION --project $PROJECT --format='table(name.basename(),schedule,state)'"
  note "Slack app: Request URLs $PUBLIC_URL/slack/events and $PUBLIC_URL/slack/interactions (see README)"
  note "register $PUBLIC_URL/admin/sources/google/callback as an authorised redirect URI on the Google OAuth client"
  note "do NOT start the laptop docs0 against the Slack app again; data is not migrated: connect Google Drive again on $PUBLIC_URL/admin/sources"
}

step_domain() {
  note "step: domain (optional, only with --step domain): map $DOMAIN to the service (same as sales0.smalltech.in)"
  run "gcloud beta run domain-mappings describe --domain $DOMAIN --region $REGION --project $PROJECT >/dev/null 2>&1 || gcloud beta run domain-mappings create --service $SERVICE --domain $DOMAIN --region $REGION --project $PROJECT"
  note "then add this DNS record at the DNS provider of ${DOMAIN#*.}:   CNAME   ${DOMAIN%%.*}   ->   ghs.googlehosted.com."
  note "(the sibling sales0.smalltech.in uses exactly that record; confirm with:"
  note " gcloud beta run domain-mappings describe --domain $DOMAIN --region $REGION --project $PROJECT --format='value(status.resourceRecords)')"
  note "the certificate is issued automatically a few minutes after DNS resolves; until then use $SERVICE_URL"
}

step_rollback() {
  if [ -z "$ROLLBACK_REV" ]; then
    note "rollback: list revisions, then re-run with 'rollback <REVISION>'"
    run "gcloud run revisions list --service $SERVICE --project $PROJECT --region $REGION --limit 5"
  else
    note "rollback: send 100% of traffic to $ROLLBACK_REV (the database is NOT rolled back; migrations only move forward; the jobs keep their image until 'deploy.sh --step jobs')"
    run "gcloud run services update-traffic $SERVICE --project $PROJECT --region $REGION --to-revisions $ROLLBACK_REV=100"
  fi
}

export REQUIRED_NAMES="${REQUIRED_ENV[*]}" OPTIONAL_NAMES="${OPTIONAL_ENV[*]}"
if [ "$ACTION" = "rollback" ]; then step_rollback; exit 0; fi
for s in "${STEPS[@]}"; do
  case "$s" in
    wif) step_wif ;;
    db-user) step_db_user ;;
    deploy) step_deploy ;;
    jobs) step_jobs ;;
    scheduler) step_scheduler ;;
    verify) step_verify ;;
    domain) step_domain ;;
    *) echo "unknown step: $s" >&2; exit 2 ;;
  esac
done
note "done"
