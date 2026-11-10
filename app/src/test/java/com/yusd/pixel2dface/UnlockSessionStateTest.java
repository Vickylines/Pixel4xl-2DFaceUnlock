package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import org.junit.Test;

public final class UnlockSessionStateTest {
    @Test public void firstWakeNearBootIsAcceptedAndDuplicateNativeBroadcastIsDebounced() {
        UnlockSessionState state = new UnlockSessionState();
        assertEquals(1L, state.wake(1L));
        assertEquals(-1L, state.wake(100L));
        assertEquals(1L, state.generation);
        // A delayed second native/broadcast wake still belongs to this readiness window.
        assertEquals(-1L, state.wake(501L));
    }

    @Test public void staleConfigurationCannotReleaseANewerWakePreparation() {
        UnlockSessionState state = new UnlockSessionState();
        long firstWake = state.wake(100L);
        assertTrue(state.beginPreparation(firstWake, 110L));
        state.reset(); // Only an actual sleep/user/policy transition creates another wake.
        long secondWake = state.wake(1_000L);
        assertTrue(state.beginPreparation(secondWake, 1_010L));
        state.finishPreparation(firstWake);
        assertTrue(state.isPreparing(secondWake));
        assertFalse(state.start("old", 1_020L, firstWake, 0));
        assertTrue(state.start("new", 1_020L, secondWake, 0));
    }

    @Test public void lockReadinessRetriesKeepOneWakeAndOnlyOnePreparation() {
        UnlockSessionState state = new UnlockSessionState();
        long wake = state.wake(100L);
        assertEquals(120L, state.readinessRetryDelay(wake, 200L, 120L));
        assertEquals(-1L, state.wake(900L));
        assertEquals(10L, state.readinessRetryDelay(wake, 1_590L, 120L));
        assertTrue(state.beginPreparation(wake, 1_599L));
        assertFalse(state.beginPreparation(wake, 1_599L));
        assertEquals(-1L, state.readinessRetryDelay(wake, 1_599L, 120L));
        assertTrue(state.start("session", 1_600L, wake, 0));
        assertFalse(state.start("second", 1_601L, wake, 0));
    }

    @Test public void readinessWindowIsBoundedAndAnOldRetryCannotPrepareAfterSleep() {
        UnlockSessionState state = new UnlockSessionState();
        long wake = state.wake(100L);
        assertEquals(-1L, state.readinessRetryDelay(wake, 99L, 120L));
        assertEquals(-1L, state.readinessRetryDelay(wake, 1_600L, 120L));
        state.finishReadiness(wake);
        assertFalse(state.isReadinessPending(wake));
        state.reset();
        long next = state.wake(2_000L);
        state.finishReadiness(wake);
        assertTrue(state.isReadinessPending(next));
        assertEquals(-1L, state.readinessRetryDelay(wake, 2_100L, 120L));
        assertFalse(state.beginPreparation(wake, 2_100L));
        assertTrue(state.beginPreparation(next, 2_100L));
    }

    @Test public void lateDuplicateWakeCannotRotateAnEstablishedCameraGrant() {
        UnlockSessionState state = runningSession();
        long wake = state.generation;
        assertEquals(-1L, state.wake(1_000L));
        assertEquals(wake, state.generation);
        assertTrue(state.matches("session"));
        state.reset();
        assertTrue(state.wake(1_001L) > wake);
    }

    @Test public void screenOffCancelsBothConfigurationAndQueuedAuthentication() {
        UnlockSessionState state = runningSession();
        assertTrue(state.results.consume("session", 200L, state.generation));
        long oldWake = state.generation;
        state.reset();
        assertFalse(state.isPreparing(oldWake));
        assertFalse(state.start("session", 201L, oldWake, 0));
        assertFalse(state.results.claimDelivery("session", 201L, oldWake, 0));
        assertFalse(state.isHostActive(201L));
    }

    @Test public void missingHostFinishCannotKeepTheOcclusionAdapterActiveForever() {
        UnlockSessionState state = runningSession();
        assertTrue(state.isHostActive(120L));
        assertFalse(state.isHostActive(120L + UnlockResultGuard.MAX_RESULT_AGE_MS + 1L));
        assertFalse(state.isHostActive(119L));
    }

    @Test public void successKeepsHostBrieflyAndExpiresPassiveAuthentication() {
        UnlockSessionState state = runningSession();
        state.authenticate(0, 200L);
        assertTrue(state.hasPassiveAuthentication(201L));
        assertTrue(state.isHostActive(20_000L));
        assertFalse(state.hasPassiveAuthentication(200L + UnlockSessionState.PASSIVE_AUTH_AGE_MS));
        assertFalse(state.isHostActive(201L + UnlockSessionState.PASSIVE_AUTH_AGE_MS));
    }

    @Test public void anOldHostCannotFinishANewSessionOrDisableItsInputTwice() {
        UnlockSessionState state = runningSession();
        assertFalse(state.consumeHostReady("other", 130L));
        assertTrue(state.consumeHostReady("session", 131L));
        assertFalse(state.consumeHostReady("session", 132L));
        state.finishHost("other");
        assertTrue(state.isHostActive(133L));
        state.finishHost("session");
        assertFalse(state.isHostActive(134L));
    }

    @Test public void finishingCameraHostPreservesAlreadyGrantedSwipeAuthentication() {
        UnlockSessionState state = runningSession();
        state.authenticate(0, 200L);
        state.finishHost("session");
        assertTrue(state.hasPassiveAuthentication(201L));
        assertFalse(state.isHostActive(201L));
        assertFalse(state.results.consume("session", 201L, state.generation));
    }

    @Test public void visualDismissalWhileDeviceStillLockedPreservesTheSwipeGrant() {
        UnlockSessionState state = runningSession();
        assertTrue(state.results.consume("session", 180L, state.generation));
        assertTrue(state.results.claimDelivery("session", 190L, state.generation, 0));
        state.authenticate(0, 200L);
        long wake = state.generation;
        assertEquals(-1L, state.confirmDeviceUnlocked(wake, true, true));
        assertEquals(-1L, state.confirmDeviceUnlocked(wake, false, true));
        assertEquals(-1L, state.confirmDeviceUnlocked(wake, true, false));
        assertTrue(state.isGeneration(wake));
        assertTrue(state.matches("session"));
        assertTrue(state.hasPassiveAuthentication(300L));
    }

    @Test public void confirmedDeviceUnlockRevokesTheGrantAndItsPendingCallbacks() {
        UnlockSessionState state = runningSession();
        state.authenticate(0, 200L);
        long wake = state.generation;
        long cancelled = state.confirmDeviceUnlocked(wake, false, false);
        assertTrue(cancelled > wake);
        assertTrue(state.isGeneration(cancelled));
        assertFalse(state.isGeneration(wake));
        assertFalse(state.matches("session"));
        assertFalse(state.hasPassiveAuthentication(300L));
    }

    @Test public void lateUnlockConfirmationAndOldCleanupCannotWithdrawANewerGrant() {
        UnlockSessionState state = runningSession();
        long previousWake = state.generation;
        state.reset();
        long oldCleanup = state.generation;
        long newWake = state.wake(1_000L);
        assertTrue(state.beginPreparation(newWake, 1_010L));
        assertTrue(state.start("new", 1_020L, newWake, 0));
        assertEquals(-1L, state.confirmDeviceUnlocked(previousWake, false, false));
        assertFalse(state.isGeneration(oldCleanup));
        assertTrue(state.isGeneration(newWake));
        assertTrue(state.matches("new"));
    }

    @Test public void userAndConfigurationChangesNeverMatchThePreviousResult() {
        UnlockSessionState state = runningSession();
        state.configRevision = 42L;
        assertTrue(state.configurationMatches(42L));
        assertFalse(state.configurationMatches(43L));
        state.authenticate(10, 200L);
        assertFalse(state.hasPassiveAuthentication(201L));
        state.reset();
        assertFalse(state.configurationMatches(42L));
    }

    @Test public void cooldownPreventsAnImmediateRetryAfterHostFailure() {
        UnlockSessionState state = runningSession();
        state.finishHost("session");
        assertFalse(state.beginPreparation(state.generation, 200L));
        assertTrue(state.beginPreparation(state.generation,
                120L + UnlockSessionState.LAUNCH_COOLDOWN_MS));
    }

    @Test public void failureRevokesTheHostAndFencesAnAlreadyConsumedResult() {
        UnlockSessionState state = runningSession();
        long wake = state.generation;
        assertTrue(state.results.consume("session", 180L, wake));
        assertTrue(state.abort("session", wake));
        assertFalse(state.isHostActive(200L));
        assertFalse(state.hasPassiveAuthentication(200L));
        assertFalse(state.results.claimDelivery("session", 200L, wake, 0));
        assertFalse(state.isGeneration(wake));
        assertEquals(120L, state.lastLaunchAt);
    }

    @Test public void oldFailureCannotCancelANewScan() {
        UnlockSessionState state = runningSession();
        long previous = state.generation;
        state.reset();
        long next = state.wake(1_000L);
        assertTrue(state.beginPreparation(next, 1_010L));
        assertTrue(state.start("next", 1_020L, next, 0));
        assertFalse(state.abort("session", previous));
        assertFalse(state.abort("session", next));
        assertTrue(state.matches("next"));
        assertTrue(state.isHostActive(1_030L));
    }

    @Test public void abortedGrantCannotBeRestoredAndDuplicateFailureDoesNothing() {
        UnlockSessionState state = runningSession();
        long wake = state.generation;
        state.authenticate(0, 200L);
        assertTrue(state.hasPassiveAuthentication(210L));
        assertTrue(state.abort("session", wake));
        assertFalse(state.hasPassiveAuthentication(211L));
        assertFalse(state.abort("session", wake));
    }

    @Test public void cooldownRejectionDoesNotLeaveReadinessPermanentlyPending() {
        UnlockSessionState state = runningSession();
        assertTrue(state.abort("session", state.generation));
        long retry = state.wake(1_000L);
        assertTrue(retry > 0L);
        assertFalse(state.beginPreparation(retry, 1_010L));
        assertFalse(state.isReadinessPending(retry));
        long next = state.wake(3_000L);
        assertTrue(next > retry);
        assertTrue(state.beginPreparation(next, 3_010L));
    }

    @Test public void hostExitDoesNotEraseSuccessWaitingForConfigurationRecheck() {
        UnlockSessionState state = runningSession();
        state.configRevision = 42L;
        long wake = state.generation;
        assertTrue(state.results.consume("session", 200L, wake));
        state.finishHost("session");
        assertFalse(state.isHostActive(201L));
        assertFalse(state.hasPassiveAuthentication(201L));
        assertTrue(state.configurationMatches(42L));
        assertTrue(state.results.claimDelivery("session", 300L, wake, 0));
        state.authenticate(0, 300L);
        assertTrue(state.hasPassiveAuthentication(301L));
        assertFalse(state.results.claimDelivery("session", 302L, wake, 0));
    }

    @Test public void lateWakeCannotReplacePendingSuccessAfterHostExit() {
        UnlockSessionState state = runningSession();
        long wake = state.generation;
        assertTrue(state.results.consume("session", 200L, wake));
        state.finishHost("session");
        assertEquals(-1L, state.wake(1_000L));
        assertTrue(state.results.mayDeliver("session", 1_001L, wake, 0));
        state.reset();
        assertFalse(state.results.claimDelivery("session", 1_002L, wake, 0));
    }

    @Test public void hostExitCannotCreateOrExtendAnAuthenticationResult() {
        UnlockSessionState state = runningSession();
        state.finishHost("session");
        assertFalse(state.results.consume("session", 200L, state.generation));
        state = runningSession();
        assertTrue(state.results.consume("session", 200L, state.generation));
        state.finishHost("session");
        assertFalse(state.results.claimDelivery("session",
                121L + UnlockResultGuard.MAX_RESULT_AGE_MS, state.generation, 0));
        assertTrue(state.wake(121L + UnlockResultGuard.MAX_RESULT_AGE_MS) > 0L);
    }

    private static UnlockSessionState runningSession() {
        UnlockSessionState state = new UnlockSessionState();
        long wake = state.wake(100L);
        assertTrue(state.beginPreparation(wake, 110L));
        assertTrue(state.start("session", 120L, wake, 0));
        return state;
    }

    @Test public void committedSwipeCannotStartHandoffUntilFaceAuthenticationExists() {
        UnlockSessionState state = runningSession();
        assertEquals(-1, state.beginBouncerHandoff(190));
        state.configRevision = 42;
        state.authenticate(0, 200);
        long attempt = state.beginBouncerHandoff(201);
        assertTrue(attempt > 0);
        assertEquals(-1, state.beginBouncerHandoff(202));
        assertTrue(state.claimBouncerHandoff(state.generation, attempt, 0, 42, 203));
        assertFalse(state.claimBouncerHandoff(state.generation, attempt, 0, 42, 204));
        state.cancelBouncerHandoff();
        assertEquals(-1, state.beginBouncerHandoff(205));
        assertEquals(200, state.authenticatedAt);
    }

    @Test public void cancelledSwipeCannotDeliverIntoItsReplacementGesture() {
        UnlockSessionState state = runningSession();
        state.configRevision = 42;
        state.authenticate(0, 200);
        long first = state.beginBouncerHandoff(201);
        state.cancelBouncerHandoff();
        assertFalse(state.isBouncerHandoffCurrent(state.generation, first, 202));
        long second = state.beginBouncerHandoff(203);
        assertTrue(second > first);
        assertFalse(state.claimBouncerHandoff(state.generation, first, 0, 42, 204));
        assertTrue(state.claimBouncerHandoff(state.generation, second, 0, 42, 205));
    }

    @Test public void handoffRechecksUserConfigurationAndAuthenticationExpiry() {
        UnlockSessionState state = runningSession();
        state.configRevision = 42;
        state.authenticate(0, 200);
        long attempt = state.beginBouncerHandoff(201);
        assertFalse(state.claimBouncerHandoff(state.generation, attempt, 10, 42, 202));
        assertFalse(state.claimBouncerHandoff(state.generation, attempt, 0, 43, 202));
        assertFalse(state.claimBouncerHandoff(state.generation, attempt, 0, 42, 199));
        assertFalse(state.claimBouncerHandoff(state.generation, attempt, 0, 42,
                200 + UnlockSessionState.PASSIVE_AUTH_AGE_MS));
        assertEquals(-1, state.beginBouncerHandoff(200 + UnlockSessionState.PASSIVE_AUTH_AGE_MS));
    }

    @Test public void sleepRevokesQueuedHandoffEvenWhenNewWakeUsesSameAttemptNumber() {
        UnlockSessionState state = runningSession();
        state.configRevision = 42;
        state.authenticate(0, 200);
        long generation = state.generation;
        long oldAttempt = state.beginBouncerHandoff(201);
        state.reset();
        long next = state.wake(1000);
        assertTrue(state.beginPreparation(next, 1010));
        assertTrue(state.start("next", 1020, next, 0));
        state.configRevision = 42;
        state.authenticate(0, 1100);
        long newAttempt = state.beginBouncerHandoff(1101);
        assertEquals(oldAttempt, newAttempt);
        assertFalse(state.claimBouncerHandoff(generation, oldAttempt, 0, 42, 1102));
        assertTrue(state.claimBouncerHandoff(next, newAttempt, 0, 42, 1103));
    }

    @Test public void cameraHostExitDoesNotLoseHandoffForAnAlreadyAuthenticatedSwipe() {
        UnlockSessionState state = runningSession();
        state.configRevision = 42;
        state.authenticate(0, 200);
        state.finishHost("session");
        long attempt = state.beginBouncerHandoff(201);
        assertTrue(state.claimBouncerHandoff(state.generation, attempt, 0, 42, 202));
        assertEquals(200, state.authenticatedAt);
    }
}
