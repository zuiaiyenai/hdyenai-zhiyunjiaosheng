import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const vus = positiveInteger(__ENV.VUS || '10', 'VUS');
const accountCount = positiveInteger(__ENV.ACCOUNT_COUNT || '1000', 'ACCOUNT_COUNT');
const warmupDuration = __ENV.WARMUP_DURATION || '2m';
const steadyDuration = __ENV.STEADY_DURATION || '10m';
const loginIntervalSeconds = positiveInteger(__ENV.LOGIN_INTERVAL_SECONDS || '30',
  'LOGIN_INTERVAL_SECONDS');
const userPrefix = required('USER_PREFIX');
const password = required('LOAD_TEST_PASSWORD');
const summaryPath = required('SUMMARY_PATH');

const loginRequests = new Counter('fctts_login_requests');
const loginDuration = new Trend('fctts_login_duration', true);
const loginErrors = new Rate('fctts_login_errors');

export const options = {
  discardResponseBodies: false,
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'avg'],
  scenarios: {
    warmup: {
      executor: 'constant-vus',
      exec: 'loginJourney',
      vus,
      duration: warmupDuration,
      gracefulStop: '30s',
      tags: { phase: 'warmup', lane: 'LOGIN_PROFILE' },
    },
    steady: {
      executor: 'constant-vus',
      exec: 'loginJourney',
      vus,
      startTime: warmupDuration,
      duration: steadyDuration,
      gracefulStop: '30s',
      tags: { phase: 'steady', lane: 'LOGIN_PROFILE' },
    },
  },
  thresholds: {
    'fctts_login_errors{phase:steady}': ['rate<0.01'],
    'fctts_login_requests{phase:steady}': ['count>0'],
    'fctts_login_duration{phase:steady}': ['p(99)<10000'],
  },
};

let initializedScenario;

export function loginJourney() {
  if (initializedScenario !== exec.scenario.name) {
    initializedScenario = exec.scenario.name;
    sleep(((__VU * 17) % loginIntervalSeconds) + Math.random());
  }
  const userIndex = ((exec.vu.idInInstance - 1) % accountCount) + 1;
  const response = http.post(`${baseUrl}/user/login`, JSON.stringify({
    username: `${userPrefix}${String(userIndex).padStart(4, '0')}`,
    password,
  }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { endpoint: 'login' },
    timeout: '10s',
  });
  let body;
  try {
    body = response.json();
  } catch (_) {
    body = null;
  }
  const ok = check(response, {
    'login status is 200': value => value.status === 200,
    'login returns token': () => body && typeof body.token === 'string' && body.token.length > 0,
  });
  const tags = { endpoint: 'login', phase: exec.scenario.name };
  loginRequests.add(1, tags);
  loginDuration.add(response.timings.duration, tags);
  loginErrors.add(!ok, tags);
  sleep(loginIntervalSeconds);
}

export function handleSummary(data) {
  return {
    stdout: `${JSON.stringify(data.metrics.fctts_login_duration || {})}\n`,
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}

function required(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}
