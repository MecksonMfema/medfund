ExUnit.start()

# Boot the chat_service supervision tree (Repo, PubSub, Endpoint). The umbrella
# `mix test` alias ensures the database exists before this runs. Channel /
# socket tests do not need DB transactions; tests that touch DB-backed handlers
# should opt into the sandbox per-test.
{:ok, _} = Application.ensure_all_started(:chat_service)
Ecto.Adapters.SQL.Sandbox.mode(ChatService.Repo, :manual)
