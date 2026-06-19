import Config

db_host = System.get_env("MEDFUND_DB_HOST", "localhost")

config :live_dashboard, ecto_repos: [LiveDashboard.Repo]

config :live_dashboard, LiveDashboard.Repo,
  username: "medfund",
  password: "medfund",
  hostname: db_host,
  database: "medfund",
  port: 5433

config :live_dashboard, LiveDashboardWeb.Endpoint,
  url: [host: "localhost"],
  secret_key_base: "CHANGE_ME_IN_PRODUCTION",
  pubsub_server: LiveDashboard.PubSub

config :chat_service, ecto_repos: [ChatService.Repo]

config :chat_service, ChatService.Repo,
  username: "medfund",
  password: "medfund",
  hostname: db_host,
  database: "medfund",
  port: 5433

config :chat_service, ChatServiceWeb.Endpoint,
  url: [host: "localhost"],
  secret_key_base: "CHANGE_ME_IN_PRODUCTION",
  pubsub_server: ChatService.PubSub

config :logger, :console,
  format: "$time $metadata[$level] $message\n",
  metadata: [:request_id, :tenant_id]

import_config "#{config_env()}.exs"
