<?php
declare(strict_types=1);

require __DIR__ . '/config.php';

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

function relay_data_dir(string $name): string {
    $path = __DIR__ . '/data/' . $name;
    if (!is_dir($path) && !mkdir($path, 0700, true) && !is_dir($path)) {
        relay_json(['ok' => false, 'error' => 'relay data directory is not writable'], 500);
    }
    return $path;
}

function relay_cleanup(string $dir, int $maxAgeSeconds): void {
    $files = glob($dir . '/*.json');
    if ($files === false) {
        return;
    }
    $cutoff = time() - $maxAgeSeconds;
    foreach ($files as $file) {
        if (@filemtime($file) < $cutoff) {
            @unlink($file);
        }
    }
}

if (WORKER_TOKEN !== '' && !hash_equals(WORKER_TOKEN, relay_token())) {
    relay_json(['ok' => false, 'error' => 'unauthorized'], 401);
}

$action = (string)($_GET['action'] ?? 'next');
$jobsDir = relay_data_dir('jobs');
$processingDir = relay_data_dir('processing');
$resultsDir = relay_data_dir('results');
relay_cleanup($resultsDir, 3600);
relay_cleanup($processingDir, 3600);

if ($action === 'status') {
    relay_json([
        'ok' => true,
        'jobs' => count(glob($jobsDir . '/*.json') ?: []),
        'processing' => count(glob($processingDir . '/*.json') ?: []),
        'results' => count(glob($resultsDir . '/*.json') ?: []),
        'generatedAt' => gmdate('c')
    ]);
}

if ($action === 'next') {
    $files = glob($jobsDir . '/*.json') ?: [];
    sort($files, SORT_STRING);
    foreach ($files as $file) {
        $id = basename($file, '.json');
        $processingPath = $processingDir . '/' . $id . '.json';
        if (!@rename($file, $processingPath)) {
            continue;
        }
        $raw = file_get_contents($processingPath);
        $job = json_decode($raw === false ? '' : $raw, true);
        if (!is_array($job)) {
            @unlink($processingPath);
            continue;
        }
        relay_json(['ok' => true, 'job' => $job]);
    }
    http_response_code(204);
    exit;
}

if ($action === 'complete') {
    $raw = file_get_contents('php://input');
    $result = json_decode($raw === false ? '' : $raw, true);
    if (!is_array($result)) {
        relay_json(['ok' => false, 'error' => 'invalid completion payload'], 400);
    }
    $id = preg_replace('/[^A-Za-z0-9._-]/', '', (string)($result['id'] ?? ''));
    if ($id === '') {
        relay_json(['ok' => false, 'error' => 'missing job id'], 400);
    }
    $resultPath = $resultsDir . '/' . $id . '.json';
    if (file_put_contents($resultPath, json_encode($result, JSON_UNESCAPED_SLASHES), LOCK_EX) === false) {
        relay_json(['ok' => false, 'error' => 'failed to write result'], 500);
    }
    @unlink($processingDir . '/' . $id . '.json');
    relay_json(['ok' => true]);
}

relay_json(['ok' => false, 'error' => 'unknown worker action'], 404);

