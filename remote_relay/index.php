<?php
declare(strict_types=1);

require __DIR__ . '/config.php';

function relay_starts_with(string $value, string $prefix): bool {
    return substr($value, 0, strlen($prefix)) === $prefix;
}

function relay_json(array $value, int $status = 200): void {
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($value, JSON_UNESCAPED_SLASHES);
    exit;
}

function relay_token(): string {
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if (stripos($header, 'Bearer ') === 0) {
        return trim(substr($header, 7));
    }
    return (string)($_GET['token'] ?? '');
}

function relay_safe_header_value(string $value): string {
    return str_replace(["\r", "\n"], '', $value);
}

function relay_phone_auth_error(string $token): string {
    $hasPermanentToken = PHONE_TOKEN !== '';
    $hasTestToken = defined('TEST_PHONE_TOKEN') && TEST_PHONE_TOKEN !== '';

    if (!$hasPermanentToken && !$hasTestToken) {
        return '';
    }
    if ($hasPermanentToken && hash_equals(PHONE_TOKEN, $token)) {
        return '';
    }
    if ($hasTestToken && hash_equals(TEST_PHONE_TOKEN, $token)) {
        $expiresAt = defined('TEST_PHONE_TOKEN_EXPIRES_AT') ? strtotime(TEST_PHONE_TOKEN_EXPIRES_AT) : false;
        if ($expiresAt !== false && time() <= $expiresAt) {
            return '';
        }
        return 'test token expired';
    }
    return 'unauthorized';
}

function relay_route(): string {
    $uri = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
    if ($uri === false || $uri === null || $uri === '') {
        $uri = '/';
    }
    $base = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? '')), '/');
    if ($base !== '' && $base !== '.' && relay_starts_with($uri, $base)) {
        $uri = substr($uri, strlen($base));
    }
    if (relay_starts_with($uri, '/index.php')) {
        $uri = substr($uri, strlen('/index.php'));
    }
    if ($uri === '' || $uri === '/') {
        return '/link';
    }
    return '/' . ltrim($uri, '/');
}

function relay_data_dir(string $name): string {
    $path = __DIR__ . '/data/' . $name;
    if (!is_dir($path) && !mkdir($path, 0700, true) && !is_dir($path)) {
        relay_json(['ok' => false, 'error' => 'relay data directory is not writable'], 500);
    }
    return $path;
}

function relay_job_id(): string {
    return gmdate('YmdHis') . '-' . bin2hex(random_bytes(8));
}

function relay_count_files(string $dir): int {
    $files = glob($dir . '/*.json');
    return $files === false ? 0 : count($files);
}

function relay_processing_stale_seconds(): int {
    return defined('PROCESSING_STALE_SECONDS') ? (int)PROCESSING_STALE_SECONDS : 180;
}

function relay_processing_jobs(string $dir): array {
    $files = glob($dir . '/*.json') ?: [];
    sort($files, SORT_STRING);
    $jobs = [];
    $now = time();
    foreach ($files as $file) {
        $raw = file_get_contents($file);
        $job = json_decode($raw === false ? '' : $raw, true);
        if (!is_array($job)) {
            continue;
        }
        $createdAt = (string)($job['createdAt'] ?? '');
        $createdAtSeconds = $createdAt === '' ? false : strtotime($createdAt);
        if ($createdAtSeconds === false) {
            $createdAtSeconds = @filemtime($file);
        }
        $age = $createdAtSeconds === false ? null : max(0, $now - $createdAtSeconds);
        $jobs[] = [
            'id' => (string)($job['id'] ?? basename($file, '.json')),
            'createdAt' => $createdAt,
            'ageSeconds' => $age,
            'method' => (string)($job['method'] ?? ''),
            'route' => (string)($job['route'] ?? '')
        ];
    }
    return $jobs;
}

function relay_fail_stale_processing(string $processingDir, string $resultsDir): void {
    $files = glob($processingDir . '/*.json') ?: [];
    $cutoff = time() - relay_processing_stale_seconds();
    foreach ($files as $file) {
        $mtime = @filemtime($file);
        if ($mtime === false || $mtime >= $cutoff) {
            continue;
        }
        $raw = file_get_contents($file);
        $job = json_decode($raw === false ? '' : $raw, true);
        if (!is_array($job)) {
            @unlink($file);
            continue;
        }
        $id = preg_replace('/[^A-Za-z0-9._-]/', '', (string)($job['id'] ?? basename($file, '.json')));
        if ($id === '') {
            @unlink($file);
            continue;
        }
        $body = json_encode([
            'ok' => false,
            'error' => 'Windows tunnel stalled before completing this request. The queued message is still on the phone; use Try now after the tunnel is healthy.'
        ], JSON_UNESCAPED_SLASHES);
        $result = [
            'id' => $id,
            'status' => 504,
            'contentType' => 'application/json; charset=utf-8',
            'bodyBase64' => base64_encode($body === false ? '{}' : $body)
        ];
        file_put_contents($resultsDir . '/' . $id . '.json', json_encode($result, JSON_UNESCAPED_SLASHES), LOCK_EX);
        @unlink($file);
    }
}

function relay_wait_for_result(string $jobId): void {
    if (function_exists('set_time_limit')) {
        @set_time_limit(PHONE_WAIT_SECONDS + 15);
    }
    $resultPath = relay_data_dir('results') . '/' . $jobId . '.json';
    $deadline = microtime(true) + PHONE_WAIT_SECONDS;
    while (microtime(true) < $deadline) {
        clearstatcache(true, $resultPath);
        if (is_file($resultPath)) {
            $raw = file_get_contents($resultPath);
            @unlink($resultPath);
            $result = json_decode($raw === false ? '' : $raw, true);
            if (!is_array($result)) {
                relay_json(['ok' => false, 'error' => 'invalid relay result'], 502);
            }
            if (!empty($result['error'])) {
                relay_json(['ok' => false, 'error' => (string)$result['error']], 502);
            }
            $status = (int)($result['status'] ?? 200);
            $contentType = (string)($result['contentType'] ?? 'application/json; charset=utf-8');
            $body = base64_decode((string)($result['bodyBase64'] ?? ''), true);
            if ($body === false) {
                relay_json(['ok' => false, 'error' => 'invalid relay body'], 502);
            }
            http_response_code($status);
            header('Content-Type: ' . $contentType);
            header('Cache-Control: no-store');
            $headers = $result['headers'] ?? [];
            if (is_array($headers)) {
                $contentDisposition = (string)($headers['Content-Disposition'] ?? $headers['content-disposition'] ?? '');
                if ($contentDisposition !== '') {
                    header('Content-Disposition: ' . relay_safe_header_value($contentDisposition));
                }
            }
            header('Content-Length: ' . strlen($body));
            echo $body;
            exit;
        }
        usleep(250000);
    }
    relay_json([
        'ok' => false,
        'pending' => true,
        'jobId' => $jobId,
        'error' => 'Timed out waiting for the Windows tunnel to answer.'
    ], 504);
}

$route = relay_route();
if ($route === '/server-health') {
    $jobsDir = relay_data_dir('jobs');
    $processingDir = relay_data_dir('processing');
    $resultsDir = relay_data_dir('results');
    relay_fail_stale_processing($processingDir, $resultsDir);
    $health = [
        'ok' => true,
        'source' => 'codex-link-relay',
        'jobs' => relay_count_files($jobsDir),
        'processing' => relay_count_files($processingDir),
        'generatedAt' => gmdate('c')
    ];
    if (relay_phone_auth_error(relay_token()) === '') {
        $health['processingJobs'] = relay_processing_jobs($processingDir);
    }
    relay_json($health);
}

if ($route === '/server-health-public') {
    relay_json([
        'ok' => true,
        'source' => 'codex-link-relay',
        'jobs' => relay_count_files(relay_data_dir('jobs')),
        'processing' => relay_count_files(relay_data_dir('processing')),
        'generatedAt' => gmdate('c')
    ]);
}

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

$authError = relay_phone_auth_error(relay_token());
if ($authError !== '') {
    relay_json(['ok' => false, 'error' => $authError], 401);
}

relay_fail_stale_processing(relay_data_dir('processing'), relay_data_dir('results'));

$jobId = relay_job_id();
$job = [
    'id' => $jobId,
    'createdAt' => gmdate('c'),
    'method' => $_SERVER['REQUEST_METHOD'] ?? 'GET',
    'route' => $route,
    'query' => $_SERVER['QUERY_STRING'] ?? '',
    'contentType' => $_SERVER['CONTENT_TYPE'] ?? '',
    'accept' => $_SERVER['HTTP_ACCEPT'] ?? 'application/json',
    'bodyBase64' => base64_encode(file_get_contents('php://input') ?: '')
];

$jobPath = relay_data_dir('jobs') . '/' . $jobId . '.json';
if (file_put_contents($jobPath, json_encode($job, JSON_UNESCAPED_SLASHES), LOCK_EX) === false) {
    relay_json(['ok' => false, 'error' => 'failed to queue relay job'], 500);
}

relay_wait_for_result($jobId);
