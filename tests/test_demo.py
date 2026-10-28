from services.demo import build_demo_job


def test_demo_job_is_flagged_and_has_all_statuses():
    job = build_demo_job()
    assert job.is_demo and job.job_id == "DEMO"
    assert "DEMO DATA" in job.java_note
    statuses = set(job.rows["status"])
    assert {"MATCH", "MISMATCH", "MISSING_IN_JAVA"} <= statuses
