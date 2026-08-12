const DEFAULT_BACKEND_ORIGIN = 'http://localhost:8000';

/**
 * Return true when hostname identifies the local machine.
 *
 * Inputs:
 * - hostname: URL hostname string.
 *
 * Returns: boolean.
 */
function isLoopbackHostname(hostname) {
  const normalized = String(hostname || '').toLowerCase();
  return normalized === 'localhost'
    || normalized === '127.0.0.1'
    || normalized === '[::1]'
    || normalized === '::1';
}

/**
 * Validate and normalize an Electron BFF URL to its origin.
 *
 * Inputs:
 * - rawUrl: absolute URL string.
 *
 * Returns: normalized origin string.
 *
 * Throws: Error for malformed URLs, non-origin paths, credentials, unsupported
 * schemes, or non-loopback HTTP origins.
 */
function normalizeBackendOrigin(rawUrl) {
  const candidate = String(rawUrl || '').trim();
  let parsed;

  try {
    parsed = new URL(candidate);
  } catch (_error) {
    throw new Error('NANOSAMURAI_API_URL must be an absolute HTTP(S) URL');
  }

  if (parsed.username || parsed.password) {
    throw new Error('NANOSAMURAI_API_URL must not contain credentials');
  }
  if (parsed.pathname !== '/' || parsed.search || parsed.hash) {
    throw new Error('NANOSAMURAI_API_URL must contain only an origin, without a path, query, or fragment');
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error('NANOSAMURAI_API_URL must use HTTP or HTTPS');
  }
  if (parsed.protocol === 'http:' && !isLoopbackHostname(parsed.hostname)) {
    throw new Error('NANOSAMURAI_API_URL must use HTTPS unless it targets loopback');
  }

  return parsed.origin;
}

/**
 * Resolve the configured BFF origin from process-style environment variables.
 *
 * Precedence:
 * 1. NANOSAMURAI_API_URL
 * 2. SAMURAIBFF_ELECTRON_DEV_URL (deprecated compatibility alias)
 * 3. http://localhost:8000
 *
 * Returns: normalized origin string.
 */
function resolveBackendOrigin(environment = {}) {
  const configured = environment.NANOSAMURAI_API_URL
    || environment.SAMURAIBFF_ELECTRON_DEV_URL
    || DEFAULT_BACKEND_ORIGIN;
  return normalizeBackendOrigin(configured);
}

/**
 * Validate a post-login application path.
 *
 * Inputs:
 * - nextPath: renderer-provided route path.
 * - backendOrigin: validated BFF origin.
 *
 * Returns: normalized internal pathname.
 *
 * Throws: Error when the path could navigate outside the BFF origin.
 */
function normalizeNextPath(nextPath, backendOrigin) {
  const candidate = String(nextPath || '/recordings').trim();
  if (!candidate.startsWith('/')
      || candidate.startsWith('//')
      || candidate.includes('\\')
      || candidate.includes('?')
      || candidate.includes('#')) {
    throw new Error('Login next path must be an internal application pathname');
  }

  const parsed = new URL(candidate, backendOrigin);
  if (parsed.origin !== backendOrigin) {
    throw new Error('Login next path must remain on the configured BFF origin');
  }
  return parsed.pathname;
}

/**
 * Return true when a URL belongs to the configured BFF origin.
 */
function isBackendUrl(candidateUrl, backendOrigin) {
  try {
    return new URL(candidateUrl).origin === backendOrigin;
  } catch (_error) {
    return false;
  }
}

/**
 * Return true when an IPC sender URL belongs to the configured BFF origin.
 */
function isAuthorizedSender(senderUrl, backendOrigin) {
  return isBackendUrl(senderUrl, backendOrigin);
}

/**
 * Return true when the main application window may navigate to a URL.
 */
function isAllowedMainNavigation(candidateUrl, backendOrigin) {
  return isBackendUrl(candidateUrl, backendOrigin);
}

/**
 * Return true when the isolated authentication window may navigate to a URL.
 *
 * HTTPS is allowed for hosted identity providers. HTTP is limited to loopback
 * for local development identity providers.
 */
function isAllowedAuthNavigation(candidateUrl, backendOrigin) {
  try {
    const parsed = new URL(candidateUrl);
    if (parsed.username || parsed.password) {
      return false;
    }
    return parsed.origin === backendOrigin
      || parsed.protocol === 'https:'
      || (parsed.protocol === 'http:' && isLoopbackHostname(parsed.hostname));
  } catch (_error) {
    return false;
  }
}

/**
 * Return true after OIDC has returned to the requested BFF application route.
 */
function isAuthCompletion(candidateUrl, backendOrigin, nextPath) {
  try {
    const parsed = new URL(candidateUrl);
    return parsed.origin === backendOrigin && parsed.pathname === nextPath;
  } catch (_error) {
    return false;
  }
}

/**
 * Create a single-settlement state holder for one OIDC child-window flow.
 *
 * Returns an object whose complete, fail, and cancel methods return true only
 * for the first terminal transition. The status method returns the current
 * state string.
 */
function createAuthFlowState() {
  let currentStatus = 'pending';

  const transition = (nextStatus) => {
    if (currentStatus !== 'pending') {
      return false;
    }
    currentStatus = nextStatus;
    return true;
  };

  return {
    cancel: () => transition('cancelled'),
    complete: () => transition('completed'),
    fail: () => transition('failed'),
    status: () => currentStatus,
  };
}

module.exports = {
  DEFAULT_BACKEND_ORIGIN,
  createAuthFlowState,
  isAllowedAuthNavigation,
  isAllowedMainNavigation,
  isAuthCompletion,
  isAuthorizedSender,
  isBackendUrl,
  isLoopbackHostname,
  normalizeBackendOrigin,
  normalizeNextPath,
  resolveBackendOrigin,
};
