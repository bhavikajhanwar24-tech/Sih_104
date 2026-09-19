/**
 * Pure break-glass transcript state machine (request → pending → approved).
 * Mirrors Decision Plane BreakGlassTranscriptService.Status.
 */

export const BREAK_GLASS_STATUS = Object.freeze({
  NONE: 'NONE',
  PENDING: 'PENDING',
  APPROVED: 'APPROVED',
});

/**
 * @param {string} status
 * @param {'request'|'approve'|'reset'} action
 * @returns {string}
 */
export function nextBreakGlassStatus(status, action) {
  switch (action) {
    case 'request':
      return status === BREAK_GLASS_STATUS.NONE || status === BREAK_GLASS_STATUS.APPROVED
        ? BREAK_GLASS_STATUS.PENDING
        : status;
    case 'approve':
      return status === BREAK_GLASS_STATUS.PENDING
        ? BREAK_GLASS_STATUS.APPROVED
        : status;
    case 'reset':
      return BREAK_GLASS_STATUS.NONE;
    default:
      return status;
  }
}

/**
 * Analyst may open a request from NONE (or after a prior approved window expires / reset).
 * @param {string} status
 * @param {boolean} isAnalyst
 * @returns {boolean}
 */
export function canRequestBreakGlass(status, isAnalyst) {
  return Boolean(isAnalyst) && (status === BREAK_GLASS_STATUS.NONE || status === BREAK_GLASS_STATUS.APPROVED);
}

/**
 * Supervisor may approve only while PENDING (and must differ from requester — enforced server-side).
 * @param {string} status
 * @param {boolean} isSupervisor
 * @returns {boolean}
 */
export function canApproveBreakGlass(status, isSupervisor) {
  return Boolean(isSupervisor) && status === BREAK_GLASS_STATUS.PENDING;
}

/**
 * Redacted ±5 s window text is visible only after APPROVED.
 * @param {string} status
 * @returns {boolean}
 */
export function canViewRedactedWindow(status) {
  return status === BREAK_GLASS_STATUS.APPROVED;
}
