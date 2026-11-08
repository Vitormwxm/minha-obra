import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AccountsApi } from './accounts-api';

export interface DeletionRequest {
  id: string;
  requestedAt: string;
}

export interface DeletionState {
  request: DeletionRequest | null;
}

export interface PendingDeletion extends DeletionRequest {
  accountId: string;
  username: string;
  displayName: string;
  email: string | null;
}

export interface DeletionPage {
  items: PendingDeletion[];
  page: number;
  size: number;
  total: number;
}

@Injectable({ providedIn: 'root' })
export class DeletionApi {
  private readonly http = inject(HttpClient);
  private readonly accounts = inject(AccountsApi);

  state(): Promise<DeletionState> {
    return firstValueFrom(this.http.get<DeletionState>('/api/account/deletion', { withCredentials: true }));
  }

  request(key: string): Promise<DeletionState> {
    return this.accounts.mutate('POST', '/account/deletion', null, key);
  }

  cancel(requestId: string, key: string): Promise<DeletionState> {
    return this.accounts.mutate('POST', '/account/deletion/cancel', { requestId }, key);
  }

  pending(page: number): Promise<DeletionPage> {
    const params = new HttpParams().set('page', page).set('size', 20);
    return firstValueFrom(this.http.get<DeletionPage>('/api/admin/deletions', { params, withCredentials: true }));
  }

  approve(id: string, key: string): Promise<void> {
    return this.accounts.mutate('POST', '/admin/deletions/' + encodeURIComponent(id) + '/approve', null, key);
  }
}
