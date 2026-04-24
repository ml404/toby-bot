-- Capture the user's TobyCoin balance alongside social credit at each monthly
-- snapshot so the wallet leaderboard can show +/- this month. Existing rows
-- get 0 — for those, "earned this month" = current balance for one month,
-- which is a reasonable bootstrap and settles correctly on the next snapshot.
ALTER TABLE monthly_credit_snapshot
    ADD COLUMN toby_coins BIGINT NOT NULL DEFAULT 0;
