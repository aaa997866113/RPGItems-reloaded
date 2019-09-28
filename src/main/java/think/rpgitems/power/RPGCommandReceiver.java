package think.rpgitems.power;

import cat.nyaa.nyaacore.LanguageRepository;
import cat.nyaa.nyaacore.Message;
import cat.nyaa.nyaacore.Pair;
import cat.nyaa.nyaacore.cmdreceiver.Arguments;
import cat.nyaa.nyaacore.cmdreceiver.CommandReceiver;
import cat.nyaa.nyaacore.cmdreceiver.SubCommand;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import think.rpgitems.RPGItems;
import think.rpgitems.item.ItemManager;
import think.rpgitems.item.RPGItem;
import think.rpgitems.power.marker.Selector;
import think.rpgitems.power.trigger.Trigger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static think.rpgitems.power.PowerManager.powers;

public abstract class RPGCommandReceiver extends CommandReceiver {
    private final Map<String, String> subCommandAttribute = new HashMap<>();
    private final LanguageRepository i18n;

    public RPGCommandReceiver(RPGItems plugin, LanguageRepository i18n) {
        super(plugin, i18n);
        Arrays.stream(getClass().getDeclaredMethods()).forEach(method -> {
            if (method.getAnnotation(Attribute.class) != null) {
                subCommandAttribute.put(method.getAnnotation(SubCommand.class).value(), method.getAnnotation(Attribute.class).value());
            }
        });
        this.i18n = i18n;
    }

    private static List<String> resolvePropertyValueSuggestion(RPGItem item, Class<? extends Power> power, String propertyName, String last, boolean hasNamePrefix) {
        try {
            return resolvePropertyValueSuggestion(item, power, power.getField(propertyName), last, hasNamePrefix);
        } catch (NoSuchFieldException e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> resolvePropertyValueSuggestion(RPGItem item, Class<? extends Power> power, Field propertyField, String last, boolean hasNamePrefix) {
        BooleanChoice bc = propertyField.getAnnotation(BooleanChoice.class);
        if (bc != null) {
            return Stream.of(bc.trueChoice(), bc.falseChoice()).map(s -> (hasNamePrefix ? propertyField.getName() + ":" : "") + s).filter(s -> s.startsWith(last)).collect(Collectors.toList());
        }
        if (Collection.class.isAssignableFrom(propertyField.getType())) {
            ParameterizedType listType = (ParameterizedType) propertyField.getGenericType();
            Class<?> listArg = (Class<?>) listType.getActualTypeArguments()[0];
            if (listArg.equals(Trigger.class)) {
                return resolveEnumListValue(power, propertyField, new ArrayList<>(Trigger.keySet()), last, hasNamePrefix);
            }
            if (!listArg.isEnum()) {
                if (propertyField.getName().equalsIgnoreCase("conditions")) {
                    List<Condition<?>> conditions = item.getConditions();
                    List<String> conditionIds = conditions.stream().map(Condition::id).collect(Collectors.toList());
                    return resolveEnumListValue(power, propertyField, conditionIds, last, hasNamePrefix);
                }
                if (propertyField.getName().equalsIgnoreCase("selectors")) {
                    List<Selector> selectors = item.getMarker(Selector.class);
                    List<String> selectorIds = selectors.stream().map(Selector::id).collect(Collectors.toList());
                    return resolveEnumListValue(power, propertyField, selectorIds, last, hasNamePrefix);
                }
                return Collections.emptyList();
            }
            List<String> enumValues = Stream.of(((Class<? extends Enum>) listArg).getEnumConstants()).map(Enum::name).collect(Collectors.toList());
            return resolveEnumListValue(power, propertyField, enumValues, last, hasNamePrefix);
        }
        AcceptedValue as = propertyField.getAnnotation(AcceptedValue.class);

        if (as != null) {
            return PowerManager.getAcceptedValue(power, as).stream().map(s -> (hasNamePrefix ? propertyField.getName() + ":" : "") + s).filter(s -> s.startsWith(last)).collect(Collectors.toList());
        }
        if (propertyField.getType().equals(boolean.class) || propertyField.getType().equals(Boolean.class)) {
            return Stream.of(true, false).map(s -> (hasNamePrefix ? propertyField.getName() + ":" : "") + s).filter(s -> s.startsWith(last)).collect(Collectors.toList());
        }
        if (propertyField.getType().isEnum()) {
            return Stream.of(propertyField.getType().getEnumConstants()).map(s -> (hasNamePrefix ? propertyField.getName() + ":" : "") + s.toString()).filter(s -> s.startsWith(last)).collect(Collectors.toList());
        }

        return Collections.emptyList();
    }

    private static List<String> resolveEnumListValue(Class<? extends Power> power, Field propertyField, List<String> enumValues, String last, boolean hasNamePrefix) {
        String currentValuesStr;
        if (hasNamePrefix) {
            currentValuesStr = last.replace(propertyField.getName() + ":", "");
        } else {
            currentValuesStr = last;
        }
        List<String> currentVaules = Stream.of(currentValuesStr.split(",")).collect(Collectors.toList());
        int size = currentVaules.size();
        String lastVaule = size > 0 ? currentVaules.get(size - 1) : "";
        if (enumValues.contains(lastVaule)) {
            lastVaule = "";
        } else {
            currentVaules.remove(size - 1);
        }
        AcceptedValue as = propertyField.getAnnotation(AcceptedValue.class);
        if (as != null) {
            List<String> acceptedValue = PowerManager.getAcceptedValue(power, as);
            enumValues.retainAll(acceptedValue);
        }
        String incompleteValue = lastVaule;

        if (Set.class.isAssignableFrom(propertyField.getType()) || (as != null && as.preset() == Preset.TRIGGERS)) {
            enumValues.removeAll(currentVaules);
        }
        return resolveEnumCompletion(enumValues, last, hasNamePrefix, currentVaules, incompleteValue);
    }

    private static List<String> resolveEnumCompletion(Collection<String> enumValues, String last, boolean hasNamePrefix, List<String> currentVaules, String incompleteValue) {
        String base = incompleteValue.isEmpty() ? last : last.replaceAll(incompleteValue + "$", "");
        boolean next = (currentVaules.isEmpty() && !hasNamePrefix) || base.endsWith(":") || base.endsWith(",");
        return enumValues.stream().filter(n -> n.startsWith(incompleteValue)).map(n -> base + (next ? "" : ",") + n).collect(Collectors.toList());
    }

    private List<String> resolvePowerOrPropertySuggestion(CommandSender sender, Arguments cmd) {
        if (cmd == null) return Collections.emptyList();
        String[] args = cmd.getRawArgs();
        if (args.length < 4) return Collections.emptyList();
        String last = args[args.length - 1];
        String[] arg = Arrays.copyOf(args, args.length - 1);
        Pair<RPGItem, String> itemCommand = resolveItemCommand(cmd.next(), cmd.next());
        if (itemCommand == null) return Collections.emptyList();
        switch (itemCommand.getValue()) {
            case "get":
            case "set": {
                return resolveGetSet(last, cmd, itemCommand);
            }
            case "power": {
                return resolvePowerProperties(sender, itemCommand.getKey(), last, cmd);
            }
            default:
                return Collections.emptyList();
        }
    }

    private List<String> resolvePowerProperties(CommandSender sender, RPGItem item, String last, Arguments cmd) {
        String powName = cmd.next();
        NamespacedKey powerKey;
        try {
            powerKey = PowerManager.parseKey(powName);
        } catch (UnknownExtensionException e) {
            return Collections.emptyList();
        }
        Class<? extends Power> power = powers.get(powerKey);
        if (power == null) return Collections.emptyList();
        Map<String, Pair<Method, PropertyInstance>> argMap = PowerManager.getProperties(power);
        Set<Field> settled = new HashSet<>();

        List<Field> required = argMap.values().stream()
                                     .map(Pair::getValue)
                                     .filter(PropertyInstance::required)
                                     .sorted(Comparator.comparing(PropertyInstance::order))
                                     .map(PropertyInstance::field)
                                     .collect(Collectors.toList());

        Meta meta = power.getAnnotation(Meta.class);

        for (Map.Entry<String, Pair<Method, PropertyInstance>> prop : argMap.entrySet()) {
            Field field = prop.getValue().getValue().field();
            String name = prop.getKey();
            String value = cmd.argString(name, null);
            if (value != null
                        || isTrivialProperty(meta, name)
            ) {
                required.remove(field);
                settled.add(field);
            }
        }
        if (settled.isEmpty()) {
            actionBarTip(sender, powerKey, null);
        }
        return resolvePropertiesSuggestions(sender, item, last, power, argMap, settled, required);
    }

    protected boolean isTrivialProperty(Meta meta, String name) {
        return (meta.immutableTrigger() && name.equals("triggers"))
                       || (meta.marker() && name.equals("triggers"))
                       || (meta.marker() && name.equals("conditions") && !meta.withConditions())
                       || (!meta.withSelectors() && name.equals("selectors"))
                       || (!meta.withContext() && name.equals("requiredContext"))
                       || name.equals("displayName");
    }

    private List<String> resolvePropertiesSuggestions(CommandSender sender, RPGItem item, String last, Class<? extends Power> power, Map<String, Pair<Method, PropertyInstance>> argMap, Set<Field> settled, List<Field> required) {
        if (argMap.keySet().stream().anyMatch(f -> last.startsWith(f + ":"))) {//we are suggesting a value as we have the complete property name
            String currentPropertyName = last.split(":")[0];
            actionBarTip(sender, powers.inverse().get(power), currentPropertyName);
            return resolvePropertyValueSuggestion(item, power, currentPropertyName, last, true);
        }
        List<String> suggestions;
        suggestions = required.stream().map(s -> s.getName() + ":").filter(s -> s.startsWith(last)).collect(Collectors.toList());
        if (!suggestions.isEmpty()) return suggestions; //required property
        suggestions = argMap.values().stream().filter(s -> !settled.contains(s.getValue().field())).map(s -> s.getValue().name() + ":").filter(s -> s.startsWith(last)).collect(Collectors.toList());
        return suggestions; //unsettled property
    }

    private void actionBarTip(CommandSender sender, NamespacedKey power, String property) {
        if (sender instanceof Player) {
            Bukkit.getScheduler().runTask(RPGItems.plugin, () -> {
                String description = PowerManager.getDescription(power, property);
                if (description == null) {
                    return;
                }
                new Message(description).send((Player) sender, Message.MessageType.ACTION_BAR);
            });
        }
    }

    private List<String> resolveGetSet(String last, Arguments cmd, Pair<RPGItem, String> itemCommand) {
        RPGItem item = itemCommand.getKey();
        String powerName = cmd.next();
        NamespacedKey key;
        try {
            key = PowerManager.parseKey(powerName);
        } catch (UnknownExtensionException e) {
            return Collections.emptyList();
        }
        List<Power> powers = item.getPowers().stream().filter(p -> item.getPowerKey(p).equals(key)).collect(Collectors.toList());
        if (powers.isEmpty()) return Collections.emptyList();
        Class<? extends Power> powerClass = powers.get(0).getClass();
        if (cmd.top().equals("")) {
            // rpgitem item get/set power
            return IntStream.rangeClosed(1, powers.size()).mapToObj(Integer::toString).collect(Collectors.toList());
        } else {
            // rpgitem item get/set power 1 ...
            cmd.next();
        }
        if (cmd.top().equals("")) {
            // rpgitem item get/set power 1
            return PowerManager.getProperties(powerClass).keySet().stream().filter(s -> s.startsWith(last)).collect(Collectors.toList());
        }
        if (itemCommand.getValue().equals("get")) return Collections.emptyList();
        // rpgitem item set power 1 property
        return resolvePropertyValueSuggestion(item, powerClass, cmd.next(), last, false);
    }

    private static Pair<RPGItem, String> resolveItemCommand(String f, String s) {
        Optional<RPGItem> rpgItem = ItemManager.getItem(f);
        if (rpgItem.isPresent()) {
            return new Pair<>(rpgItem.get(), s);
        }
        rpgItem = ItemManager.getItem(s);
        return rpgItem.map(r -> new Pair<>(r, f)).orElse(null);
    }

    private static List<String> resolveSet(Set<String> values, String last) {
        List<String> currentVaules = Stream.of(last.split(",")).collect(Collectors.toList());
        String lastVaule = currentVaules.get(currentVaules.size() - 1);
        if (values.contains(lastVaule)) {
            lastVaule = "";
        } else {
            currentVaules.remove(currentVaules.size() - 1);
        }
        values.removeAll(currentVaules);
        String incompleteValue = lastVaule;
        return resolveEnumCompletion(values, last, false, currentVaules, incompleteValue);
    }

    @Override
    public List<String> acceptTabComplete(CommandSender sender, Arguments cmd) {
        boolean suggestion = cmd.isSuggestion();
        Set<String> subCommands = getSubCommands();
        if (suggestion) {
            switch (cmd.length() - 1) {
                case 0:
                    return Collections.emptyList();
                case 1: {
                    String str = cmd.next();
                    if (ItemManager.getItem(str).isPresent()) {
                        // we have a `/rpgitem item` and waiting a proper command
                        return subCommandAttribute.entrySet().stream().filter(entry -> Stream.of("item", "power", "property").anyMatch(entry.getValue()::startsWith)).map(Map.Entry::getKey).collect(Collectors.toList());
                    } else {
                        // we have a `/rpgitem command` and waiting a argument
                        String attr = subCommandAttribute.get(str);
                        if (attr == null) return Collections.emptyList();
                        if (attr.startsWith("command")) {
                            // it's a global command and we have suggestion in attr
                            String[] att = attr.split(":", 2);
                            if (att.length > 1) {
                                return Arrays.asList(att[1].split(","));
                            }
                            return Collections.emptyList();
                        } else {
                            // it's a item command, just items
                            return new ArrayList<>(ItemManager.itemNames());
                        }
                    }
                }
                case 2: {
                    String first = cmd.next();
                    String second = cmd.next();
                    // may be `/rpgitem item command` or `/rpgitem command item`
                    Pair<RPGItem, String> itemCommand = resolveItemCommand(first, second);
                    if (itemCommand == null) return Collections.emptyList(); // neither
                    String attr = subCommandAttribute.get(itemCommand.getValue());
                    if (attr == null) return Collections.emptyList();
                    String[] att = attr.split(":", 2);
                    switch (att[0]) {
                        case "property":
                        case "power":
                            // four case below
                            switch (itemCommand.getValue()) {
                                case "power":
                                    return powers.keySet().stream().map(s -> PowerManager.hasExtension() ? s : s.getKey()).map(Object::toString).collect(Collectors.toList()); // all powers
                                case "set":
                                case "get":
                                case "removepower":
                                    return itemCommand.getKey().getPowers().stream().map(itemCommand.getKey()::getPowerKey).map(s -> PowerManager.hasExtension() ? s : s.getKey()).map(Object::toString).collect(Collectors.toList()); // current powers
                                default:
                                    return Collections.emptyList();
                            }
                        case "item":
                        case "command": {
                            return att.length > 1 ? Arrays.asList(att[1].split(",")) : null; // suggestion bundled in attr
                        }
                        default:
                            return null;
                    }
                }
                default:
                    return resolvePowerOrPropertySuggestion(sender, cmd); // only case is `/rpgitem power item somepower`
            }
        } else {
            switch (cmd.length()) {
                case 0:
                    return subCommandAttribute.entrySet().stream().filter(entry -> entry.getValue().startsWith("command")).map(Map.Entry::getKey).collect(Collectors.toList());
                case 1: {
                    String str = cmd.next();
                    // trying to complete a `/rpgitem com` or `/rpgitem ite`
                    List<String> list = subCommands.stream().filter(s -> s.startsWith(str)).collect(Collectors.toList());
                    if (!list.isEmpty()) return list;
                    return ItemManager.itemNames().stream().filter(s -> s.startsWith(str)).collect(Collectors.toList());
                }
                case 2: {
                    String first = cmd.next();
                    String second = cmd.next();
                    if (ItemManager.getItem(first).isPresent()) {
                        // trying to complete `/rpgitem item com`
                        return subCommandAttribute.entrySet().stream()
                                                  .filter(entry -> Stream.of("item", "power", "property").anyMatch(entry.getValue()::startsWith))
                                                  .map(Map.Entry::getKey)
                                                  .filter(s -> s.startsWith(second))
                                                  .collect(Collectors.toList());
                    } else {
                        // trying to complete `/rpgitem commmand argu`
                        String attr = subCommandAttribute.get(first);
                        if (attr == null) return Collections.emptyList();
                        String[] att = attr.split(":", 2);
                        switch (att[0]) {
                            case "property":
                            case "power":
                            case "item": {
                                return ItemManager.itemNames().stream().filter(s -> s.startsWith(second)).collect(Collectors.toList()); // items
                            }
                            case "items": {
                                return resolveSet(ItemManager.itemNames(), second); // items
                            }
                            case "command": {
                                return att.length > 1 ? Arrays.stream(att[1].split(",")).filter(s -> s.startsWith(second)).collect(Collectors.toList()) : null; // bundled in attr
                            }
                            default:
                                return Collections.emptyList();
                        }
                    }
                }
                case 3: {
                    String first = cmd.next();
                    String second = cmd.next();
                    String third = cmd.next();
                    Pair<RPGItem, String> itemCommand = resolveItemCommand(first, second);
                    if (itemCommand == null) return Collections.emptyList();
                    String attr = subCommandAttribute.get(itemCommand.getValue());
                    if (attr == null) return Collections.emptyList();
                    String[] att = attr.split(":", 2);
                    switch (att[0]) {
                        case "property":
                        case "power":
                            switch (itemCommand.getValue()) {
                                case "power":
                                    return powers.keySet().stream()
                                                 .filter(s -> s.getKey().startsWith(third) || s.toString().startsWith(third))
                                                 .map(s -> PowerManager.hasExtension() ? s : s.getKey())
                                                 .map(Object::toString).collect(Collectors.toList()); // only case is `/rpgitem power item somepow`
                                case "set":
                                case "get":
                                case "removepower":
                                    return itemCommand.getKey()
                                                      .getPowers()
                                                      .stream()
                                                      .map(itemCommand.getKey()::getPowerKey)
                                                      .filter(s -> s.getKey().startsWith(third) || s.toString().startsWith(third))
                                                      .map(s -> PowerManager.hasExtension() ? s : s.getKey())
                                                      .map(Object::toString)
                                                      .collect(Collectors.toList()); // complete current powers
                                default:
                                    return Collections.emptyList();
                            }
                        case "item": {
                            return att.length > 1 ? Arrays.stream(att[1].split(",")).filter(s -> s.startsWith(third)).collect(Collectors.toList()) : null; // bundled
                        }
                        default:
                            return Collections.emptyList();
                    }

                }
                default:
                    return resolvePowerOrPropertySuggestion(sender, cmd);
            }
        }
    }

    protected void msg(CommandSender target, String template, Map<String, BaseComponent> map, Object... args) {
        new Message("").append(i18n.getFormatted(template, args), map).send(target);
    }

    @Override
    protected boolean showCompleteMessage() {
        return false;
    }
}