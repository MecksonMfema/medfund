ExUnit.start()

# The umbrella's `mix test` alias runs ecto.create/migrate before tests so the
# repo is always reachable. Channel/PubSub tests do not touch the database, but
# the SQL sandbox keeps the Repo in a known state for the tests that do.
{:ok, _} = Application.ensure_all_started(:live_dashboard)
Ecto.Adapters.SQL.Sandbox.mode(LiveDashboard.Repo, :manual)
