/**
 * OTP wire shapes. Only the request/response envelopes are shared: what an app
 * folds them into is genuinely divergent (admin-web resolves screens/roleCodes,
 * parent/teacher/driver resolve subjectId via /v1/iam/me), so each app keeps
 * its own `verifyOtp` that builds its own Session.
 */

import type { ApiClient } from "./http";

export type AuthTokensDto = {
  accessToken: string;
  refreshToken: string;
  profile: {
    userAccountId: string;
    subjectType: string;
    schoolId: string;
    chainSchema: string;
  };
};

export type MeDto = {
  userAccountId: string;
  subjectType: string;
  subjectId: string;
  schoolId: string;
};

export type AuthApi = {
  startOtp: (identifier: string, chainSlug: string) => Promise<void>;
  verifyOtpTokens: (identifier: string, chainSlug: string, code: string) => Promise<AuthTokensDto>;
  fetchMe: () => Promise<MeDto>;
};

export function createAuthApi(client: ApiClient): AuthApi {
  return {
    async startOtp(identifier, chainSlug) {
      await client.apiFetch<{ status: string }>("/v1/auth/otp/start", {
        method: "POST",
        body: JSON.stringify({ identifier, chainSlug }),
      });
    },
    verifyOtpTokens(identifier, chainSlug, code) {
      return client.apiFetch<AuthTokensDto>("/v1/auth/otp/verify", {
        method: "POST",
        body: JSON.stringify({ identifier, chainSlug, code }),
      });
    },
    fetchMe() {
      return client.apiFetch<MeDto>("/v1/iam/me");
    },
  };
}
