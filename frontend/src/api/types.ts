// Mirrors the backend DTOs (ItemDtos.java and friends).

export type Status = 'OPEN' | 'IN_PROGRESS' | 'AWAITING_APPROVAL' | 'RESOLVED' | 'CLOSED';
export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';
export type ItemType = 'INCIDENT' | 'CUSTOMER_ISSUE' | 'ENGINEERING' | 'PAYMENT' | 'COMPLIANCE' | 'TASK';
export type TeamRole = 'VIEWER' | 'MEMBER' | 'LEAD';

export const STATUSES: Status[] = ['OPEN', 'IN_PROGRESS', 'AWAITING_APPROVAL', 'RESOLVED', 'CLOSED'];
export const PRIORITIES: Priority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];
export const ITEM_TYPES: ItemType[] = ['INCIDENT', 'CUSTOMER_ISSUE', 'ENGINEERING', 'PAYMENT', 'COMPLIANCE', 'TASK'];

export interface UserRef {
  id: number;
  name: string;
}

export interface Me {
  id: number;
  email: string;
  displayName: string;
  admin: boolean;
  teams: { id: number; name: string; role: TeamRole }[];
}

export interface TeamMember {
  id: number;
  name: string;
  role: TeamRole;
}

export interface WorkItemSummary {
  id: number;
  teamId: number;
  teamName: string;
  title: string;
  type: ItemType;
  priority: Priority;
  status: Status;
  assignee?: UserRef;
  dueAt?: string;
  overdue: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface TransitionOption {
  to: Status;
  action: string;
  noteRequired: boolean;
}

export interface Permissions {
  canEdit: boolean;
  canClaim: boolean;
  canAssign: boolean;
  canComment: boolean;
  transitions: TransitionOption[];
}

export interface WorkItemDetail extends WorkItemSummary {
  description: string;
  createdBy: UserRef;
  permissions: Permissions;
}

export interface Page<T> {
  items: T[];
  nextCursor?: string;
}

export interface DashboardSummary {
  mine: number;
  unassignedUrgent: number;
  awaitingMyApproval: number;
  overdue: number;
}

export interface ActivityEntry {
  id: number;
  type: string;
  data: Record<string, unknown>;
  actorId?: number;
  actorName?: string;
  createdAt: string;
}

export interface CommentEntry {
  id: number;
  body: string;
  authorId: number;
  authorName: string;
  createdAt: string;
}

export interface NotificationEntry {
  id: number;
  itemId?: number;
  message: string;
  read: boolean;
  createdAt: string;
}

export interface NotificationPage {
  items: NotificationEntry[];
  unreadCount: number;
}

export interface ItemFilter {
  view?: string;
  teamId?: string;
  status?: string;
  priority?: string;
  type?: string;
  assignee?: string;
  q?: string;
}
