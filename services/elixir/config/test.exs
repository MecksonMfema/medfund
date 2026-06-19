import Config

db_host = System.get_env("MEDFUND_DB_HOST", "localhost")

# Use a dedicated test DB so dev data is never touched. SQL.Sandbox wraps
# each test in a rolled-back transaction; the database itself must exist
# (created by `mix ecto.create` in test env) but its contents stay clean.
config :live_dashboard, LiveDashboard.Repo,
  username: "medfund",
  password: "medfund",
  hostname: db_host,
  database: "medfund_elixir_test",
  port: 5433,
  pool: Ecto.Adapters.SQL.Sandbox,
  pool_size: 10

config :chat_service, ChatService.Repo,
  username: "medfund",
  password: "medfund",
  hostname: db_host,
  database: "medfund_elixir_test",
  port: 5433,
  pool: Ecto.Adapters.SQL.Sandbox,
  pool_size: 10

# Suppress the Phoenix endpoint web server during tests — channels and
# controllers are exercised via Phoenix.ChannelTest / ConnTest without
# a real socket.
config :live_dashboard, LiveDashboardWeb.Endpoint, server: false
config :chat_service, ChatServiceWeb.Endpoint, server: false

# Quieter test logs.
config :logger, level: :warning
