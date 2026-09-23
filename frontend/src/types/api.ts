export type Role = 'STUDENT' | 'PROFESSOR' | 'REGISTRAR'
export type SemesterStatus = 'PREPARATION' | 'OPEN' | 'CLOSED'
export type OfferingStatus = 'OPEN' | 'CLOSED' | 'CANCELLED'
export type ScheduleStatus = 'DRAFT' | 'REGISTERED' | 'WITHDRAWN' | 'FINALIZED' | 'EXPIRED'
export type GradeValue = 'A' | 'B' | 'C' | 'D' | 'F' | 'I'

export interface ApiEnvelope<T> {
  data: T
  requestId?: string
}
export interface PageResult<T> {
  items: T[]
  page: number
  pageSize: number
  total: number
  snapshotVerifiedAt?: string
  departments?: string[]
}
export interface ApiErrorBody {
  code?: string
  message?: string
  details?: unknown
  requestId?: string
}

export interface CurrentUser {
  accountId: string
  personId?: string
  role: Role
  displayName: string
  username: string
}

export interface Semester {
  id: string
  code: string
  name: string
  startDate: string
  endDate: string
  registrationStart: string
  registrationEnd: string
  addDropEnd: string
  status: SemesterStatus
  currency: string
  version: number
}

export interface Meeting {
  weekNo: number
  weekday: number
  startPeriod: number
  endPeriodExclusive: number
  room?: string
}

export interface Offering {
  id: string
  semesterId: string
  courseId?: string
  courseCode: string
  courseName: string
  department: string
  credits: number
  sectionCode: string
  professorId?: string
  professorName?: string
  prerequisites: string[]
  meetings: Meeting[]
  enrolledCount: number
  maxStudents: number
  minStudents: number
  status: OfferingStatus
  tuition: string
  currency: string
  version: number
  cancelReason?: string | null
}

export interface ScheduleChoice {
  offeringId: string
  kind: 'PRIMARY' | 'ALTERNATE'
  priority: number
}

export interface Enrollment {
  id: string
  offeringId: string
  state: 'ENROLLED' | 'DROPPED' | 'CANCELLED'
  source: 'PRIMARY' | 'ALTERNATE'
  reason?: string
  offering?: Offering
}

export interface Schedule {
  id?: string
  semesterId: string
  status: ScheduleStatus
  version: number
  draftRevision?: number | null
  submittedRevision?: number | null
  latestRevision?: number
  firstSubmittedAt?: string | null
  draftChoices: ScheduleChoice[]
  submittedChoices: ScheduleChoice[]
  enrollments: Enrollment[]
}

export interface ValidationIssue {
  code: string
  field?: string
  message: string
  offeringId?: string
}

export interface ScheduleValidation {
  valid: boolean
  issues: ValidationIssue[]
  affectedOfferings?: Offering[]
}

export interface ReportCardItem {
  enrollmentId: string
  offeringId: string
  courseCode: string
  courseName: string
  sectionCode: string
  professorName?: string
  grade: GradeValue | null
  version: number | null
}

export interface ReportCard {
  semesterId: string
  semesterName: string
  items: ReportCardItem[]
}

export interface TeachingPlan {
  version: number
  eligibleOfferings: Offering[]
  selectedOfferings: Offering[]
  selectedOfferingIds: string[]
}

export interface RosterStudent {
  enrollmentId: string
  studentId: string
  studentName: string
  grade: GradeValue | null
  version: number | null
}

export interface Roster {
  offering: Offering
  canEditGrades: boolean
  students: RosterStudent[]
}

export interface PersonRecord {
  id: string
  accountId?: string
  username?: string
  name: string
  birthDate: string
  status: string
  graduationDate?: string | null
  department?: string
  ssnMasked?: string | null
  version: number
}

export interface CloseResult {
  runId: string
  semesterId: string
  closedAt: string
  cancelledOfferings: number
  leveledSchedules: number
  billingEvents: number
  pendingBillingEvents: number
  summary?: Record<string, unknown>
  offerings: {
    offeringId: string
    courseName: string
    sectionCode: string
    status: OfferingStatus
    enrolledCount: number
    cancelReason?: string
  }[]
}

export interface BillingEvent {
  eventId: string
  studentId: string
  studentName?: string
  state: 'PENDING' | 'IN_FLIGHT' | 'RETRY' | 'DELIVERED'
  attempts: number
  nextAttemptAt?: string | null
  lastError?: string | null
  deliveredAt?: string | null
}
