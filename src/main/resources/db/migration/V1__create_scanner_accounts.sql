CREATE TABLE users (
    username varchar(100) PRIMARY KEY,
    password varchar(500) NOT NULL,
    enabled boolean NOT NULL
);

CREATE TABLE authorities (
    username varchar(100) NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    authority varchar(50) NOT NULL
);

CREATE UNIQUE INDEX ix_auth_username ON authorities (username, authority);
