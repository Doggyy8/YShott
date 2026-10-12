# YShott

Native Android family planner in Kotlin and Jetpack Compose.

## Current status
This branch is an explicit local example version. It is not yet a production service. Calendar month/week/day, tasks with parent approval, points and rewards, shopping and staples, meals and cooking steps, local proposals, residence examples, child pictograms and Dutch speech are implemented for build verification.

Real email authentication, household membership approval, multi-account synchronization, external calendar subscriptions, online chat, AI recipe lookup, location sharing and push notifications remain to be connected and tested. Medication is excluded.

## Build
Java 17, Android SDK 35, Gradle 8.9. GitHub Actions runs unit tests, lint and assembleDebug and retains the test APK for 30 days. A debug APK is for testing; stable release signing must be configured before distributing durable updates.

## Data and backups
Example data is encrypted locally using Android Keystore. Exported JSON backups contain readable data: store them privately. Production data must be isolated by household and enforce permissions on the server.

## Development
Package: nl.yshott.app. Minimum Android 8 (API 26). Dependencies are pinned in Gradle files. Keep source in GitHub; create a branch, run checks, review changes and preserve the release signing key before publishing updates.

## Budget
No paid infrastructure is provisioned by this source commit. Free services have usage limits. Credentials and private signing keys must never be committed.
