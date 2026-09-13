import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const vus = positiveInteger(__ENV.VUS || '10', 'VUS');
const accountCount = positiveInteger(__ENV.ACCOUNT_COUNT || '1000', 'ACCOUNT_COUNT');
const warmupDuration = __ENV.WARMUP_DURATION || '2m';
const steadyDuration = __ENV.STEADY_DURATION || '10m';
const steadyDurationSeconds = durationSeconds(steadyDuration);
const runId = required('RUN_ID');
const userPrefix = required('USER_PREFIX');
const password = required('LOAD_TEST_PASSWORD');
const summaryPath = required('SUMMARY_PATH');
const taskSharePercent = numberInRange(__ENV.TASK_SHARE_PERCENT || '20', 0, 100,
  'TASK_SHARE_PERCENT');
const ttsIntervalSeconds = numberInRange(__ENV.TTS_INTERVAL_SECONDS || '0', 0, 3600,
  'TTS_INTERVAL_SECONDS');
const seed = hash(`${runId}:${__ENV.SEED || runId}`);

const l0Requests = new Counter('fctts_l0_requests');
const l0Duration = new Trend('fctts_l0_duration', true);
const l0Errors = new Rate('fctts_l0_errors');
const businessErrors = new Counter('fctts_business_errors');
const taskAccepted = new Counter('fctts_task_accepted');
const taskDuplicate = new Counter('fctts_task_duplicate');
const taskRejected = new Counter('fctts_task_rejected');
const taskTerminal = new Counter('fctts_task_terminal');
const ttsRequests = new Counter('fctts_tts_requests');
const ttsDuration = new Trend('fctts_tts_duration', true);
const ttsErrors = new Rate('fctts_tts_errors');

const endpoints = [
  'login',
  'voice_list',
  'voice_search',
  'courseware_list',
  'task_create',
  'task_status',
];

const thresholds = {
  fctts_l0_duration: ['p(50)<100', 'p(95)<300', 'p(99)<800'],
  fctts_l0_errors: ['rate<0.01'],
};

for (const endpoint of endpoints) {
  thresholds[`fctts_l0_duration{endpoint:${endpoint}}`] = [
    'p(50)<100',
    'p(95)<300',
    'p(99)<800',
  ];
  thresholds[`fctts_l0_errors{endpoint:${endpoint}}`] = ['rate<0.01'];
  thresholds[`fctts_l0_requests{endpoint:${endpoint}}`] = ['count>0'];
}
if (ttsIntervalSeconds > 0) {
  thresholds.fctts_tts_duration = ['p(95)<30000', 'p(99)<30000'];
  thresholds.fctts_tts_errors = ['rate<0.01'];
  thresholds.fctts_tts_requests = ['count>0'];
}

export const options = {
  discardResponseBodies: false,
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'avg'],
  scenarios: {
    warmup: {
      executor: 'constant-vus',
      exec: 'journey',
      vus,
      duration: warmupDuration,
      gracefulStop: '0s',
      tags: { phase: 'warmup', lane: 'L0' },
    },
    steady: {
      executor: 'constant-vus',
      exec: 'journey',
      vus,
      startTime: warmupDuration,
      duration: steadyDuration,
      gracefulStop: '70s',
      tags: { phase: 'steady', lane: 'L0' },
    },
  },
  thresholds,
};

let state;
let stateScenario;

export function journey() {
  if (!state || stateScenario !== exec.scenario.name) {
    state = initializeVu();
    stateScenario = exec.scenario.name;
  }
  if (!state.initialDelayDone) {
    state.initialDelayDone = true;
    sleep(randomBetween(state, 0, 10));
  }
  if (!state.token) {
    state.token = login(state);
    if (!state.token) {
      sleep(1);
      return;
    }
  }

  const voiceViews = 1 + randomInt(state, 3);
  for (let index = 0; index < voiceViews; index += 1) {
    request(state, 'voice_list', 'GET', '/voice_library/list?page=0&size=20', null, 200,
      body => body && Array.isArray(body.content));
    sleep(randomBetween(state, 1, 3));
  }

  request(state, 'voice_search', 'GET',
    '/voice_library/search?name=phase11-voice-001&page=0&size=5', null, 200,
    body => body && Array.isArray(body.content));
  sleep(randomBetween(state, 1, 2));

  const projectViews = 1 + randomInt(state, 2);
  for (let index = 0; index < projectViews; index += 1) {
    request(state, 'courseware_list', 'GET', '/courseware/projects?page=0&size=20', null, 200,
      body => body && Array.isArray(body.content));
    sleep(randomBetween(state, 2, 5));
  }

  if (!state.taskSubmitted && state.submitTask) {
    state.taskSubmitted = true;
    const submission = request(state, 'task_create', 'POST',
      `/courseware/projects/${state.projectId}/optimize/tasks`,
      { instruction: `phase11-${runId}-${exec.scenario.name}-${state.userIndex}` }, 202,
      body => body && typeof body.taskId === 'string' && body.taskId.length > 0);
    if (submission.ok && submission.body) {
      state.taskId = submission.body.taskId;
      taskAccepted.add(1, { endpoint: 'task_create' });
      if (submission.body.duplicate === true) {
        taskDuplicate.add(1, { endpoint: 'task_create' });
      }
      pollTask(state);
    } else {
      taskRejected.add(1, { endpoint: 'task_create' });
    }
  }

  maybeSynthesize(state);

  sleep(randomBetween(state, 3, 8));
}

function initializeVu() {
  const userIndex = ((exec.vu.idInInstance - 1) % Math.min(vus, accountCount)) + 1;
  const projectNumber = userIndex * 100 + 1;
  return {
    userIndex,
    username: `${userPrefix}${String(userIndex).padStart(4, '0')}`,
    projectId: `00000000-0000-4000-8000-${String(projectNumber).padStart(12, '0')}`,
    token: null,
    taskId: null,
    taskSubmitted: false,
    lastTtsAtSeconds: 0,
    initialDelayDone: false,
    submitTask: selectedForPercent(userIndex, taskSharePercent),
    randomState: (seed ^ (userIndex * 2654435761)) >>> 0,
  };
}

function maybeSynthesize(vuState) {
  if (ttsIntervalSeconds <= 0 || vuState.userIndex !== 1) {
    return;
  }
  const nowSeconds = Date.now() / 1000;
  if (vuState.lastTtsAtSeconds > 0 &&
      nowSeconds - vuState.lastTtsAtSeconds < ttsIntervalSeconds) {
    return;
  }
  vuState.lastTtsAtSeconds = nowSeconds;
  const tags = { lane: 'TTS', endpoint: 'tts', phase: exec.scenario.name };
  const response = http.post(`${baseUrl}/voice/synthesize`, JSON.stringify({
    text: '欢迎来到课堂',
    voice: 'longxiao',
  }), {
    headers: {
      Accept: 'audio/wav',
      Authorization: `Bearer ${vuState.token}`,
      'Content-Type': 'application/json',
    },
    tags,
    timeout: '30s',
    responseType: 'binary',
  });
  const bytes = response.body ? new Uint8Array(response.body) : new Uint8Array(0);
  const wav = bytes.length >= 44 && bytes[0] === 82 && bytes[1] === 73 &&
    bytes[2] === 70 && bytes[3] === 70;
  const ok = response.status === 200 && wav;
  check(response, {
    'tts HTTP 200': () => response.status === 200,
    'tts WAV payload': () => wav,
  }, tags);
  if (exec.scenario.name === 'steady') {
    ttsRequests.add(1);
    ttsDuration.add(response.timings.duration);
    ttsErrors.add(!ok);
  }
}

function selectedForPercent(index, percent) {
  return Math.floor((index * percent) / 100) >
    Math.floor(((index - 1) * percent) / 100);
}

function login(vuState) {
  const result = request(vuState, 'login', 'POST', '/user/login', {
    username: vuState.username,
    password,
  }, 200, body => body && typeof body.token === 'string' && body.token.length > 0, false);
  return result.ok && result.body ? result.body.token : null;
}

function pollTask(vuState) {
  const delays = [1, 2, 3, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 4];
  for (const delay of delays) {
    sleep(delay);
    const result = request(vuState, 'task_status', 'GET',
      `/api/tasks/${vuState.taskId}`, null, 200,
      body => body && body.id === vuState.taskId && typeof body.status === 'string');
    if (!result.ok || !result.body) {
      return;
    }
    if (['SUCCESS', 'FAILED', 'CANCELLED'].includes(result.body.status)) {
      taskTerminal.add(1, { status: result.body.status });
      return;
    }
  }
}

function request(vuState, endpoint, method, path, body, expectedStatus, validateBody,
  authenticated = true) {
  const headers = { Accept: 'application/json' };
  let payload = null;
  if (body !== null) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  if (authenticated && vuState.token) {
    headers.Authorization = `Bearer ${vuState.token}`;
  }
  const tags = { lane: 'L0', endpoint, phase: exec.scenario.name };
  const response = http.request(method, `${baseUrl}${path}`, payload, {
    headers,
    tags,
    timeout: '10s',
    responseType: 'text',
  });
  let parsed = null;
  try {
    parsed = response.body ? JSON.parse(response.body) : null;
  } catch (_) {
    parsed = null;
  }
  const statusOk = response.status === expectedStatus;
  const bodyOk = statusOk && (!validateBody || validateBody(parsed));
  const ok = statusOk && bodyOk;
  check(response, {
    [`${endpoint} HTTP ${expectedStatus}`]: () => statusOk,
    [`${endpoint} response schema`]: () => bodyOk,
  }, tags);
  if (exec.scenario.name === 'steady') {
    l0Requests.add(1, { endpoint });
    l0Duration.add(response.timings.duration, { endpoint });
    l0Errors.add(!ok, { endpoint });
    if (!ok) {
      businessErrors.add(1, {
        endpoint,
        status: String(response.status),
        error_code: parsed && parsed.code ? String(parsed.code) : 'UNKNOWN',
      });
    }
  }
  return { ok, body: parsed, status: response.status };
}

export function handleSummary(data) {
  data.phase11 = {
    runId,
    seed,
    vus,
    accountCount,
    warmupDuration,
    steadyDuration,
    steadyDurationSeconds,
    baseUrl,
    taskSharePercent,
    ttsIntervalSeconds,
    lane: 'L0_CORE_API',
  };
  return {
    [summaryPath]: JSON.stringify(data, null, 2),
    stdout: JSON.stringify({
      runId,
      vus,
      thresholdsPassed: !Object.values(data.metrics)
        .some(metric => metric.thresholds && Object.values(metric.thresholds)
          .some(result => !result.ok)),
      summaryPath,
    }) + '\n',
  };
}

function random(vuState) {
  vuState.randomState = (1664525 * vuState.randomState + 1013904223) >>> 0;
  return vuState.randomState / 4294967296;
}

function randomInt(vuState, maxExclusive) {
  return Math.floor(random(vuState) * maxExclusive);
}

function randomBetween(vuState, min, max) {
  return min + random(vuState) * (max - min);
}

function hash(value) {
  let result = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    result ^= value.charCodeAt(index);
    result = Math.imul(result, 16777619);
  }
  return result >>> 0;
}

function durationSeconds(value) {
  const match = /^(\d+)(s|m|h)$/.exec(value);
  if (!match) {
    throw new Error(`STEADY_DURATION must use an integer s/m/h value: ${value}`);
  }
  const multiplier = match[2] === 'h' ? 3600 : match[2] === 'm' ? 60 : 1;
  return Number(match[1]) * multiplier;
}

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 1) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

function numberInRange(value, min, max, name) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < min || parsed > max) {
    throw new Error(`${name} must be between ${min} and ${max}`);
  }
  return parsed;
}

function required(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}
