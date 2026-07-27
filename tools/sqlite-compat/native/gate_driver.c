#include "sqlite3.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

enum {
    EXIT_USAGE = 2,
    EXIT_UNSUPPORTED = 20,
    EXIT_GATE_FAILURE = 21
};

static void print_db_error(const char *stage, sqlite3 *db, int rc) {
    const char *message = db == NULL ? "database handle unavailable" : sqlite3_errmsg(db);
    fprintf(stderr, "error:%s:%d:%s\n", stage, rc, message);
}

static int execute(sqlite3 *db, const char *stage, const char *sql) {
    char *message = NULL;
    int rc = sqlite3_exec(db, sql, NULL, NULL, &message);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "error:%s:%d:%s\n", stage, rc, message == NULL ? sqlite3_errmsg(db) : message);
        sqlite3_free(message);
        return rc;
    }
    return SQLITE_OK;
}

static int scalar_int(sqlite3 *db, const char *stage, const char *sql, sqlite3_int64 *value) {
    sqlite3_stmt *statement = NULL;
    int rc = sqlite3_prepare_v2(db, sql, -1, &statement, NULL);
    if (rc != SQLITE_OK) {
        print_db_error(stage, db, rc);
        return rc;
    }
    rc = sqlite3_step(statement);
    if (rc != SQLITE_ROW) {
        print_db_error(stage, db, rc);
        sqlite3_finalize(statement);
        return rc;
    }
    *value = sqlite3_column_int64(statement, 0);
    rc = sqlite3_finalize(statement);
    if (rc != SQLITE_OK) {
        print_db_error(stage, db, rc);
    }
    return rc;
}

static int scalar_text(sqlite3 *db, const char *stage, const char *sql, char *value, size_t value_size) {
    sqlite3_stmt *statement = NULL;
    const unsigned char *text;
    int rc = sqlite3_prepare_v2(db, sql, -1, &statement, NULL);
    if (rc != SQLITE_OK) {
        print_db_error(stage, db, rc);
        return rc;
    }
    rc = sqlite3_step(statement);
    if (rc != SQLITE_ROW) {
        print_db_error(stage, db, rc);
        sqlite3_finalize(statement);
        return rc;
    }
    text = sqlite3_column_text(statement, 0);
    if (text == NULL) {
        value[0] = '\0';
    } else {
        snprintf(value, value_size, "%s", (const char *) text);
    }
    rc = sqlite3_finalize(statement);
    if (rc != SQLITE_OK) {
        print_db_error(stage, db, rc);
    }
    return rc;
}

static int open_database(const char *path, int flags, sqlite3 **db, const char *stage) {
    int rc = sqlite3_open_v2(path, db, flags, NULL);
    if (rc != SQLITE_OK) {
        print_db_error(stage, *db, rc);
        if (*db != NULL) {
            sqlite3_close(*db);
            *db = NULL;
        }
    }
    return rc;
}

static int enable_wal(sqlite3 *db, const char *stage) {
    char mode[32];
    int rc = scalar_text(db, stage, "PRAGMA journal_mode=WAL", mode, sizeof(mode));
    if (rc != SQLITE_OK) {
        return EXIT_GATE_FAILURE;
    }
    if (strcmp(mode, "wal") != 0) {
        fprintf(stderr, "unsupported:wal:%s\n", mode);
        return EXIT_UNSUPPORTED;
    }
    if (execute(db, "wal-autocheckpoint", "PRAGMA wal_autocheckpoint=0") != SQLITE_OK ||
        execute(db, "wal-synchronous", "PRAGMA synchronous=FULL") != SQLITE_OK) {
        return EXIT_GATE_FAILURE;
    }
    return 0;
}

static int identity(void) {
    printf("version=%s\n", sqlite3_libversion());
    printf("source_id=%s\n", sqlite3_sourceid());
    printf("threadsafe=%d\n", sqlite3_threadsafe());
    return 0;
}

static int readonly_reopen(const char *path) {
    sqlite3 *db = NULL;
    sqlite3_int64 activities = -1;
    sqlite3_int64 points = -1;
    char *message = NULL;
    int rc;

    if (open_database(path, SQLITE_OPEN_READONLY, &db, "readonly-open") != SQLITE_OK) {
        return EXIT_GATE_FAILURE;
    }
    if (scalar_int(db, "readonly-activities", "SELECT count(*) FROM ACTIVITY", &activities) != SQLITE_OK ||
        scalar_int(db, "readonly-points", "SELECT count(*) FROM GPS_POINTS", &points) != SQLITE_OK) {
        sqlite3_close(db);
        return EXIT_GATE_FAILURE;
    }
    if (activities != 2 || points != 3) {
        fprintf(stderr, "error:readonly-counts:%lld:%lld\n",
                (long long) activities, (long long) points);
        sqlite3_close(db);
        return EXIT_GATE_FAILURE;
    }

    rc = sqlite3_exec(db, "INSERT INTO ACTIVITY(GMTSTART) VALUES('forbidden')", NULL, NULL, &message);
    if ((rc & 0xff) != SQLITE_READONLY) {
        fprintf(stderr, "error:readonly-write:%d:%s\n", rc,
                message == NULL ? sqlite3_errmsg(db) : message);
        sqlite3_free(message);
        sqlite3_close(db);
        return EXIT_GATE_FAILURE;
    }
    sqlite3_free(message);
    if (sqlite3_close(db) != SQLITE_OK) {
        return EXIT_GATE_FAILURE;
    }
    printf("readonly=ok\n");
    return 0;
}

static int wal_snapshot(const char *path) {
    sqlite3 *writer = NULL;
    sqlite3 *reader = NULL;
    sqlite3_int64 before = -1;
    sqlite3_int64 snapshot = -1;
    sqlite3_int64 after = -1;
    int result = EXIT_GATE_FAILURE;
    int wal_result;

    if (open_database(path, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, &writer, "wal-writer-open") != SQLITE_OK) {
        goto done;
    }
    wal_result = enable_wal(writer, "wal-mode");
    if (wal_result != 0) {
        result = wal_result;
        goto done;
    }
    if (execute(writer, "wal-schema",
                "CREATE TABLE WAL_PROBE(ID INTEGER PRIMARY KEY, VALUE TEXT);"
                "DELETE FROM WAL_PROBE;"
                "INSERT INTO WAL_PROBE(ID, VALUE) VALUES(1, 'before');") != SQLITE_OK) {
        goto done;
    }
    if (open_database(path, SQLITE_OPEN_READONLY, &reader, "wal-reader-open") != SQLITE_OK) {
        goto done;
    }
    if (execute(reader, "wal-reader-begin", "BEGIN") != SQLITE_OK ||
        scalar_int(reader, "wal-before", "SELECT count(*) FROM WAL_PROBE", &before) != SQLITE_OK) {
        goto done;
    }
    if (execute(writer, "wal-writer-commit",
                "BEGIN IMMEDIATE;"
                "INSERT INTO WAL_PROBE(ID, VALUE) VALUES(2, 'after');"
                "COMMIT;") != SQLITE_OK) {
        goto done;
    }
    if (scalar_int(reader, "wal-snapshot", "SELECT count(*) FROM WAL_PROBE", &snapshot) != SQLITE_OK ||
        execute(reader, "wal-reader-commit", "COMMIT") != SQLITE_OK ||
        scalar_int(reader, "wal-after", "SELECT count(*) FROM WAL_PROBE", &after) != SQLITE_OK) {
        goto done;
    }
    if (before != 1 || snapshot != 1 || after != 2) {
        fprintf(stderr, "error:wal-counts:%lld:%lld:%lld\n",
                (long long) before, (long long) snapshot, (long long) after);
        goto done;
    }
    printf("wal_snapshot=ok\n");
    result = 0;

done:
    if (reader != NULL) {
        sqlite3_close(reader);
    }
    if (writer != NULL) {
        sqlite3_close(writer);
    }
    return result;
}

static int child_transaction(const char *path, int commit_transaction, int identifier) {
    sqlite3 *db = NULL;
    char sql[512];
    int rc;

    if (open_database(path, SQLITE_OPEN_READWRITE, &db, "receipt-child-open") != SQLITE_OK) {
        _exit(70);
    }
    snprintf(sql, sizeof(sql),
             "BEGIN IMMEDIATE;"
             "INSERT INTO RECEIPT_POINTS(ID, VALUE) VALUES(%d, 'point');"
             "INSERT INTO RECEIPTS(ID, VALUE) VALUES(%d, 'receipt');%s",
             identifier, identifier, commit_transaction ? "COMMIT;" : "");
    rc = execute(db, commit_transaction ? "receipt-child-commit" : "receipt-child-interrupt", sql);
    if (rc != SQLITE_OK) {
        _exit(71);
    }
    if (commit_transaction) {
        _exit(0);
    }
    _exit(91);
}

static int wait_for_child(pid_t child, int expected_status, const char *stage) {
    int status = 0;
    if (waitpid(child, &status, 0) < 0) {
        fprintf(stderr, "error:%s:waitpid:%s\n", stage, strerror(errno));
        return EXIT_GATE_FAILURE;
    }
    if (!WIFEXITED(status) || WEXITSTATUS(status) != expected_status) {
        fprintf(stderr, "error:%s:status:%d\n", stage, status);
        return EXIT_GATE_FAILURE;
    }
    return 0;
}

static int interrupted_receipt(const char *path) {
    sqlite3 *db = NULL;
    sqlite3_int64 points = -1;
    sqlite3_int64 receipts = -1;
    char integrity[32];
    char *wal_path = NULL;
    size_t wal_path_size;
    pid_t child;
    int result = EXIT_GATE_FAILURE;
    int wal_result;

    if (open_database(path, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, &db, "receipt-setup-open") != SQLITE_OK) {
        goto done;
    }
    wal_result = enable_wal(db, "receipt-wal-mode");
    if (wal_result != 0) {
        result = wal_result;
        goto done;
    }
    if (execute(db, "receipt-schema",
                "CREATE TABLE RECEIPT_POINTS(ID INTEGER PRIMARY KEY, VALUE TEXT);"
                "CREATE TABLE RECEIPTS(ID INTEGER PRIMARY KEY, VALUE TEXT);"
                "DELETE FROM RECEIPT_POINTS;"
                "DELETE FROM RECEIPTS;") != SQLITE_OK) {
        goto done;
    }
    sqlite3_close(db);
    db = NULL;

    child = fork();
    if (child < 0) {
        fprintf(stderr, "error:receipt-interrupt-fork:%s\n", strerror(errno));
        goto done;
    }
    if (child == 0) {
        child_transaction(path, 0, 1);
    }
    if (wait_for_child(child, 91, "receipt-interrupt-wait") != 0) {
        goto done;
    }
    if (open_database(path, SQLITE_OPEN_READWRITE, &db, "receipt-after-interrupt-open") != SQLITE_OK ||
        scalar_int(db, "receipt-interrupt-points", "SELECT count(*) FROM RECEIPT_POINTS", &points) != SQLITE_OK ||
        scalar_int(db, "receipt-interrupt-receipts", "SELECT count(*) FROM RECEIPTS", &receipts) != SQLITE_OK) {
        goto done;
    }
    if (points != 0 || receipts != 0) {
        fprintf(stderr, "error:receipt-rollback:%lld:%lld\n",
                (long long) points, (long long) receipts);
        goto done;
    }
    sqlite3_close(db);
    db = NULL;

    child = fork();
    if (child < 0) {
        fprintf(stderr, "error:receipt-commit-fork:%s\n", strerror(errno));
        goto done;
    }
    if (child == 0) {
        child_transaction(path, 1, 2);
    }
    if (wait_for_child(child, 0, "receipt-commit-wait") != 0) {
        goto done;
    }

    wal_path_size = strlen(path) + 5;
    wal_path = (char *) malloc(wal_path_size);
    if (wal_path == NULL) {
        fprintf(stderr, "error:receipt-wal-path:allocation\n");
        goto done;
    }
    snprintf(wal_path, wal_path_size, "%s-wal", path);
    if (access(wal_path, F_OK) != 0) {
        fprintf(stderr, "error:receipt-wal-missing:%s\n", strerror(errno));
        goto done;
    }

    if (open_database(path, SQLITE_OPEN_READWRITE, &db, "receipt-replay-open") != SQLITE_OK ||
        scalar_int(db, "receipt-replay-points", "SELECT count(*) FROM RECEIPT_POINTS", &points) != SQLITE_OK ||
        scalar_int(db, "receipt-replay-receipts", "SELECT count(*) FROM RECEIPTS", &receipts) != SQLITE_OK) {
        goto done;
    }
    if (points != 1 || receipts != 1) {
        fprintf(stderr, "error:receipt-replay:%lld:%lld\n",
                (long long) points, (long long) receipts);
        goto done;
    }
    if (scalar_text(db, "receipt-integrity", "PRAGMA integrity_check", integrity, sizeof(integrity)) != SQLITE_OK) {
        goto done;
    }
    if (strcmp(integrity, "ok") != 0) {
        fprintf(stderr, "error:receipt-integrity-result:%s\n", integrity);
        goto done;
    }
    printf("interrupted_receipt=ok\n");
    result = 0;

done:
    free(wal_path);
    if (db != NULL) {
        sqlite3_close(db);
    }
    return result;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "identity") == 0) {
        return identity();
    }
    if (argc == 3 && strcmp(argv[1], "readonly") == 0) {
        return readonly_reopen(argv[2]);
    }
    if (argc == 3 && strcmp(argv[1], "wal") == 0) {
        return wal_snapshot(argv[2]);
    }
    if (argc == 3 && strcmp(argv[1], "interrupt") == 0) {
        return interrupted_receipt(argv[2]);
    }
    fprintf(stderr, "usage: gate_driver identity|readonly DB|wal DB|interrupt DB\n");
    return EXIT_USAGE;
}
