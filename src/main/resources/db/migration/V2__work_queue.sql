-- ForgeGuard M2: columns the work queue needs. Applies on top of V1 with
-- existing M1 runs in place; V1 is never edited (Flyway checksums it).

ALTER TABLE runs
    -- 0 for the first execution. Only reclaiming an expired lease increments
    -- it, so a run that survived one worker crash reads 1.
    ADD COLUMN attempt   INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    -- Absolute path of the task YAML this run was submitted from. A worker
    -- reloads it for the spec and the producer's patch and report paths, which
    -- tasks.spec_json does not hold. NULL on M1 runs, which never recorded it.
    ADD COLUMN task_file TEXT,
    -- The Sandbox that executed the run (e.g. 'local'), which determines which
    -- limits were actually enforced. NULL until a worker executes the run.
    ADD COLUMN sandbox   TEXT;

-- M1 wired exactly one sandbox: ForgeGuardCli built LocalProcessSandbox.
UPDATE runs SET sandbox = 'local';
