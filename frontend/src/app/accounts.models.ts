export interface Account {
  id: string;
  username: string;
  displayName: string;
  role: 'ADMIN' | 'USER';
  status: 'ACTIVE' | 'BLOCKED' | 'PENDING_APPROVAL';
  mustChangePassword: boolean;
  version: number;
  createdAt: string;
}

export interface AuthSession {
  user: Account;
  stage: 'CHANGE_PASSWORD' | 'MFA_SETUP' | 'MFA_VERIFY' | 'AUTHENTICATED';
}

export interface AccountPage {
  items: Account[];
  page: number;
  size: number;
  total: number;
}

export interface MfaSetup {
  secret: string;
  otpauthUri: string;
}

export interface MfaConfirmation {
  session: AuthSession;
  recoveryCodes: string[];
}

export interface AccountRequest {
  username: string;
  displayName: string;
  password: string;
}

export interface AccountRequestResult {
  status: 'PENDING_APPROVAL';
}

export interface ApiFailure {
  code?: string;
  message?: string;
  fieldErrors?: Record<string, string>;
}
