// Enumerations used by forms and filters, plus the shapes of the main API responses
// (documentation for editors via JSDoc; they mirror ItemDtos.java on the server).

export const STATUSES = ['OPEN', 'IN_PROGRESS', 'AWAITING_APPROVAL', 'RESOLVED', 'CLOSED'];
export const PRIORITIES = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];
export const ITEM_TYPES = ['INCIDENT', 'CUSTOMER_ISSUE', 'ENGINEERING', 'PAYMENT', 'COMPLIANCE', 'TASK'];

/**
 * @typedef {{ id: number, name: string }} UserRef
 *
 * @typedef {object} WorkItemSummary
 * @property {number} id
 * @property {number} teamId
 * @property {string} teamName
 * @property {string} title
 * @property {string} type       one of ITEM_TYPES
 * @property {string} priority   one of PRIORITIES
 * @property {string} status     one of STATUSES
 * @property {UserRef} [assignee]
 * @property {string} [dueAt]    ISO instant
 * @property {boolean} overdue
 * @property {number} version    optimistic-locking version; send it back with every change
 * @property {string} createdAt
 * @property {string} updatedAt
 *
 * @typedef {{ to: string, action: string, noteRequired: boolean }} TransitionOption
 *
 * @typedef {object} Permissions  what the current user may do, computed by the server's own rules
 * @property {boolean} canEdit
 * @property {boolean} canClaim
 * @property {boolean} canAssign
 * @property {boolean} canComment
 * @property {TransitionOption[]} transitions
 *
 * @typedef {WorkItemSummary & { description: string, createdBy: UserRef, permissions: Permissions }} WorkItemDetail
 *
 * @typedef {{ items: any[], nextCursor?: string }} Page
 */
