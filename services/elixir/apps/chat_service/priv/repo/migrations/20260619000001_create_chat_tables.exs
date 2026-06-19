defmodule ChatService.Repo.Migrations.CreateChatTables do
  use Ecto.Migration

  def change do
    create table(:chat_rooms, primary_key: false) do
      add :id, :binary_id, primary_key: true, default: fragment("gen_random_uuid()")
      add :tenant_id, :string, null: false
      add :name, :string, null: false
      add :room_type, :string, null: false, default: "support"
      add :participants, {:array, :string}, null: false, default: []

      timestamps(type: :utc_datetime)
    end

    create index(:chat_rooms, [:tenant_id])

    create table(:chat_messages, primary_key: false) do
      add :id, :binary_id, primary_key: true, default: fragment("gen_random_uuid()")
      add :room_id, :binary_id, null: false
      add :user_id, :string, null: false
      add :body, :text, null: false
      add :message_type, :string, null: false, default: "text"
      add :metadata, :map, null: false, default: %{}

      timestamps(type: :utc_datetime)
    end

    create index(:chat_messages, [:room_id, :inserted_at])

    create table(:chat_read_receipts, primary_key: false) do
      add :id, :binary_id, primary_key: true, default: fragment("gen_random_uuid()")
      add :room_id, :binary_id, null: false
      add :user_id, :string, null: false
      add :last_read_message_id, :binary_id
      add :last_read_at, :utc_datetime

      timestamps(type: :utc_datetime)
    end

    create unique_index(:chat_read_receipts, [:room_id, :user_id])
  end
end
