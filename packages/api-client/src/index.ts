export { ApiError, createApiClient } from "./http";
export type { ApiClient, ApiClientOptions } from "./http";

export { createAuthApi } from "./auth";
export type { AuthApi, AuthTokensDto, MeDto } from "./auth";

export {
  createAssessmentApi,
  createAttendanceApi,
  createCommsApi,
  createFeesApi,
  createLmsApi,
  createPeopleApi,
  createTenancyApi,
} from "./domain";

export type * from "./types";
