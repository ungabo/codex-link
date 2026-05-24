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
    return '';
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

if (PHONE_TOKEN !== '' && !hash_equals(PHONE_TOKEN, relay_token())) {
    relay_json(['ok' => false, 'error' => 'unauthorized'], 401);
}

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

