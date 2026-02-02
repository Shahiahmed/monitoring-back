-- 1) Roles
CREATE TABLE IF NOT EXISTS roles
(
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code      varchar(64) NOT NULL,          -- например: SUPER_ADMIN / ADMIN / USER
    name_en   varchar(255),
    name_kz   varchar(255),
    name_ru   varchar(255)
    );

CREATE UNIQUE INDEX IF NOT EXISTS ux_roles_code ON roles (code);

-- Индексы по локализациям делай только если реально фильтруешь/ищешь по ним
-- CREATE INDEX IF NOT EXISTS idx_roles_name_en ON roles (name_en);
-- CREATE INDEX IF NOT EXISTS idx_roles_name_kz ON roles (name_kz);
-- CREATE INDEX IF NOT EXISTS idx_roles_name_ru ON roles (name_ru);


-- 2) Users (email-only)
CREATE TABLE IF NOT EXISTS users
(
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    is_active         boolean NOT NULL DEFAULT true,

    avatar            bytea,

    email             varchar(255) NOT NULL,   -- login по email
    password_hash     varchar(255) NOT NULL,   -- хранить bcrypt/argon2 hash

    first_name        varchar(255),
    last_name         varchar(255),
    second_name       varchar(255),

    mail_token        varchar(255),
    password_hint     varchar(255),

    registration_date timestamptz NOT NULL DEFAULT now(),
    last_login_date   timestamptz
    );

-- Уникальность email
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_email ON users (email);

-- mail_token обычно лучше уникальным (если используешь подтверждение/сброс)
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_mail_token
    ON users (mail_token)
    WHERE mail_token IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_users_is_active ON users (is_active);

-- Индексы по ФИО оставляй только если есть поиск/фильтры
CREATE INDEX IF NOT EXISTS idx_users_first_name  ON users (first_name);
CREATE INDEX IF NOT EXISTS idx_users_last_name   ON users (last_name);
CREATE INDEX IF NOT EXISTS idx_users_second_name ON users (second_name);


-- 3) User <-> Roles (many-to-many)
CREATE TABLE IF NOT EXISTS user_roles
(
    user_id bigint NOT NULL,
    role_id bigint NOT NULL,
    PRIMARY KEY (user_id, role_id),

    CONSTRAINT fk_user_roles_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE,

    CONSTRAINT fk_user_roles_role
    FOREIGN KEY (role_id) REFERENCES roles(id)
    ON DELETE CASCADE
    );

-- Для запросов "все пользователи с ролью X" лучше композитный индекс:
CREATE INDEX IF NOT EXISTS idx_user_roles_role_user ON user_roles (role_id, user_id);
